/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.simulcast;

import java.beans.*;
import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

import org.jitsi.util.*;
import org.jitsi.util.event.*;

/**
 * A peer has a SimulcastReceiver per participant. It is coupled with a
 * <tt>SimulcastSender</tt> and it decides which packets (based on SSRC) to
 * accept from a peer.
 *
 * TODO move to libjitsi
 *
 * @author George Politis
 */
public abstract class SimulcastReceiver
    implements PropertyChangeListener
{
    /**
     * The <tt>SimulcastReceiverOptions</tt> to use when creating a new
     * <tt>SimulcastReceiver</tt>.
     */
    public static final SimulcastReceiverOptions initOptions;

    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(SimulcastReceiver.class);
    /**
     * Defines how many packets of the next layer must be seen before switching
     * to that layer. This value is appropriate for the base layer and needs to
     * be adjusted for use with upper layers, if one wants to achieve
     * (approximately) the same timeout for layers of different order.
     */
    public static int MAX_NEXT_SEEN = 5;

    /**
     * The name of the property which can be used to control the
     * <tt>MAX_NEXT_SEEN</tt> constant.
     */
    public static final String MAX_NEXT_SEEN_PNAME =
        SimulcastReceiver.class.getName() + ".MAX_NEXT_SEEN";

    static
    {
        // Static initialization is performed once per class-loader. So, this
        // method can be considered thread safe for our purposes.

        initOptions = new SimulcastReceiverOptions();
        initOptions.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ);
        // options.setUrgent(false);
        // options.setHardSwitch(false);
    }

    /**
     * The sync root object for synchronizing access to the receive layers.
     */
    private final Object receiveLayersSyncRoot = new Object();

    private final WeakReference<BasicSimulcastSender> weakSender;

    /**
     * Holds the number of packets of the next layer have been seen so far.
     */
    private int seenNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that is
     * currently being received.
     */
    private WeakReference<SimulcastLayer> weakCurrent;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that will be
     * (possibly) received next.
     */
    private WeakReference<SimulcastLayer> weakNext;

    /**
     * A <tt>WeakReference</tt> to the <tt>SimulcastLayer</tt> that overrides
     * the layer that is currently being received. Originally introduced for the
     * <tt>SimulcastAdaptor</tt>.
     */
    private WeakReference<SimulcastLayer> weakOverride;

    /**
     * The <tt>PropertyChangeListener</tt> implementation employed by this
     * instance to listen to changes in the values of properties of interest to
     * this instance. For example, listens to <tt>Conference</tt> in order to
     * notify about changes in the list of <tt>Endpoint</tt>s participating in
     * the multipoint conference. The implementation keeps a
     * <tt>WeakReference</tt> to this instance and automatically removes itself
     * from <tt>PropertyChangeNotifier</tt>s.
     */
    private final PropertyChangeListener weakPropertyChangeListener
        = new WeakReferencePropertyChangeListener(this);

    public SimulcastReceiver(BasicSimulcastSender sender)
    {
        this.weakSender = new WeakReference<BasicSimulcastSender>(sender);

        // Listen for property changes.
        sender.addPropertyChangeListener(weakPropertyChangeListener);
        onPeerLayersChanged(sender);
    }

    public abstract void askForKeyframe(SimulcastLayer layer);

    public abstract void onSimulcastLayersChanging(SimulcastLayer layer);

    public abstract void onSimulcastLayersChanged(SimulcastLayer layer);

    public abstract void onNextSimulcastLayerStopped(SimulcastLayer layer);

    public SimulcastLayer getSimulcastLayer(Integer nextOrder)
    {
        SimulcastSender sender = getSender();
        return sender == null ? null : sender.getSimulcastLayer(nextOrder);
    }

    private BasicSimulcastSender getSender()
    {
        WeakReference<BasicSimulcastSender> ws = this.weakSender;
        return ws == null ? null : ws.get();
    }

    /**
     *
     * @param ssrc
     * @return
     */
    public boolean accept(long ssrc)
    {
        SimulcastLayer current = getCurrent();
        boolean accept = false;

        if (current != null)
            accept = current.accept(ssrc);

        if (!accept)
        {
            SimulcastLayer next = getNext();

            if (next != null)
            {
                accept = next.accept(ssrc);
                if (accept)
                    maybeSwitchToNext();
            }
        }

        SimulcastLayer override = getOverride();

        if (override != null)
            accept = override.accept(ssrc);

        if (!accept)
        {
            // For SRTP replay protection the webrtc.org implementation uses a
            // replay database with extended range, using a rollover counter
            // (ROC) which counts the number of times the RTP sequence number
            // carried in the RTP packet has rolled over.
            //
            // In this way, the ROC extends the 16-bit RTP sequence number to a
            // 48-bit "SRTP packet index". The ROC is not be explicitly
            // exchanged between the SRTP endpoints because in all practical
            // situations a rollover of the RTP sequence number can be detected
            // unless 2^15 consecutive RTP packets are lost.
            //
            // For every 0x800 (2048) dropped packets (at most), send 8 packets
            // so that the receiving endpoint can update its ROC.
            //
            // TODO(gp) We may want to move this code somewhere more centralized
            // to take into account last-n etc.

            Integer key = Integer.valueOf((int) ssrc);
            CyclicCounter counter = dropped.getOrCreate(key, 0x800);
            accept = counter.cyclicallyIncrementAndGet() < 8;
        }

        return accept;
    }


    static class CyclicCounter {

        private final int maxVal;
        private final AtomicInteger ai = new AtomicInteger(0);

        public CyclicCounter(int maxVal) {
            this.maxVal = maxVal;
        }

        public int cyclicallyIncrementAndGet() {
            int curVal, newVal;
            do {
                curVal = this.ai.get();
                newVal = (curVal + 1) % this.maxVal;
                // note that this doesn't guarantee fairness
            } while (!this.ai.compareAndSet(curVal, newVal));
            return newVal;
        }

    }

    /**
     * Multitone pattern with Lazy Initialization.
     */
    static class CyclicCounters
    {
        private final Map<Integer, CyclicCounter> instances
            = new ConcurrentHashMap<Integer, CyclicCounter>();
        private Lock createLock = new ReentrantLock();

        CyclicCounter getOrCreate(Integer key, int maxVal) {
            CyclicCounter instance = instances.get(key);
            if (instance == null) {
                createLock.lock();
                try {
                    if (instance == null) {
                        instance = new CyclicCounter(maxVal);
                        instances.put(key, instance);
                    }
                } finally {
                    createLock.unlock();
                }
            }
            return instance;
        }
    }

    private final CyclicCounters dropped = new CyclicCounters();

    /**
     * Sets the receiving simulcast substream for the peers in the endpoints
     * parameter.
     *
     * @param options
     */
    public void configure(SimulcastReceiverOptions options)
    {
        synchronized (receiveLayersSyncRoot)
        {
            this.maybeConfigureOverride(options);
            this.maybeConfigureNext(options);
        }
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that is currently being received.
     *
     * @return
     */
    public SimulcastLayer getCurrent()
    {
        WeakReference<SimulcastLayer> wr = this.weakCurrent;
        return (wr != null) ? wr.get() : null;
    }

    public long getIncomingBitrate(boolean noOverride)
    {
        long bitrate = 0;

        if (!noOverride)
        {
            synchronized (receiveLayersSyncRoot)
            {
                SimulcastLayer override = getOverride();
                if (override != null)
                {
                    bitrate = override.getBitrate();
                }
                else
                {
                    SimulcastLayer current = getCurrent();
                    if (current != null)
                    {
                        bitrate = current.getBitrate();
                    }
                }
            }
        }
        else
        {
            SimulcastLayer current = getCurrent();
            if (current != null)
            {
                bitrate = current.getBitrate();
            }
        }

        return bitrate;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that was previously being received.
     *
     * @return
     */
    private SimulcastLayer getNext()
    {
        WeakReference<SimulcastLayer> wr = this.weakNext;
        return (wr != null) ? wr.get() : null;
    }

    /**
     * Gets the <tt>SimulcastLayer</tt> that overrides the layer that is
     * currently being received. Originally introduced for the
     * <tt>SimulcastAdaptor</tt>.
     *
     * @return
     */
    private SimulcastLayer getOverride()
    {
        WeakReference<SimulcastLayer> wr = this.weakOverride;
        return (wr != null) ? wr.get() : null;
    }

    private void maybeConfigureNext(SimulcastReceiverOptions options)
    {
        if (options == null)
        {
            logger.warn("cannot configure next simulcast layer because the " +
                    "parameter is null.");
            return;
        }

        Integer nextOrder = options.getNextOrder();
        if (nextOrder == null)
        {
            return;
        }

        SimulcastSender sender = getSender();
        if (sender == null || !sender.hasLayers())
        {
            logger.warn("doesn't have any simulcast layers.");
            return;
        }

        SimulcastLayer next
                = getSimulcastLayer(options.getNextOrder());

        // Do NOT switch to hq if it's not streaming.
        if (next == null
                || (next.getOrder()
                != SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ
                && !next.isStreaming()))
        {
            return;
        }

        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer current = getCurrent();

            // Do NOT switch to an already receiving layer.
            if (current == next)
            {
                // and forget "previous" next, we're sticking with current.
                this.weakNext = null;
                this.seenNext = 0;


                return;
            }
            else
            {
                // If current has changed, request an FIR, notify the parent
                // endpoint and change the receiving streams.

                if (options.isHardSwitch() && next != getNext())
                {
                    // XXX(gp) run these in the event dispatcher thread?

                    // Send FIR requests first.
                    if (getOverride() == null)
                    {
                        this.askForKeyframe(next);
                    }
                    else
                    {
                    }
                }


                if (options.isUrgent() || current == null || MAX_NEXT_SEEN < 1)
                {
                    // Receiving simulcast layers have brutally changed. Create
                    // and send an event through data channels to the receiving
                    // endpoint.
                    if (getOverride() == null)
                    {
                        this.onSimulcastLayersChanged(next);
                    }
                    else
                    {
                    }

                    this.weakCurrent = new WeakReference<SimulcastLayer>(next);
                    this.weakNext = null;

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;
                }
                else
                {
                    // Receiving simulcast layers are changing, create and send
                    // an event through data channels to the receiving endpoint.
                    if (getOverride() == null)
                    {
                        this.onSimulcastLayersChanging(next);
                    }
                    else
                    {
                    }

                    // If the layer we receive has changed (hasn't dropped),
                    // then continue streaming the previous layer for a short
                    // period of time while the client receives adjusts its
                    // video.
                    this.weakNext = new WeakReference<SimulcastLayer>(next);

                    // Since the currently received layer has changed, reset the
                    // seenCurrent counter.
                    this.seenNext = 0;
                }
            }
        }
    }

    private void maybeConfigureOverride(SimulcastReceiverOptions options)
    {
        if (options == null)
        {
            return;
        }

        Integer overrideOrder = options.getOverrideOrder();
        if (overrideOrder == null)
        {
            return;
        }

        SimulcastSender sender = this.getSender();

        if (sender == null || !sender.hasLayers())
        {
            return;
        }

        if (overrideOrder == SimulcastLayer.SIMULCAST_LAYER_ORDER_NO_OVERRIDE)
        {
            synchronized (receiveLayersSyncRoot)
            {
                this.weakOverride = null;
                SimulcastLayer current = getCurrent();
                if (current != null)
                {
                    this.askForKeyframe(current);
                    this.onSimulcastLayersChanged(current);
                }
            }
        }
        else
        {
            SimulcastLayer override = getSimulcastLayer(overrideOrder);
            if (override != null)
            {
                synchronized (receiveLayersSyncRoot)
                {
                    this.weakOverride
                            = new WeakReference<SimulcastLayer>(override);
                    this.askForKeyframe(override);
                    this.onSimulcastLayersChanged(override);
                }
            }

        }
    }

    private void maybeForgetNext()
    {
        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer next = getNext();
            if (next != null && !next.isStreaming())
            {
                this.weakNext = null;
                this.seenNext = 0;
                onNextSimulcastLayerStopped(next);
            }
        }
    }

    /**
     * Configures this <tt>SimulcastReceiver</tt> to receive the high quality
     * stream from the associated sender.
     */
    public void maybeReceiveHigh()
    {
        SimulcastReceiverOptions options = new SimulcastReceiverOptions();

        options.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_HQ);
        options.setHardSwitch(true);
        // options.setUrgent(false);

        configure(options);
    }

    /**
     * Configures this <tt>SimulcastReceiver</tt> to receive the low quality
     * stream from the associated sender.
     */
    public void receiveLow()
    {
        SimulcastReceiverOptions options = new SimulcastReceiverOptions();

        options.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ);
        options.setHardSwitch(true);
        // options.setUrgent(false);

        configure(options);
    }

    /**
     *
     */
    private void maybeSwitchToNext()
    {
        synchronized (receiveLayersSyncRoot)
        {
            SimulcastLayer next = getNext();

            // If there is a previous layer to timeout, and we have received
            // "enough" packets from the current layer, expire the previous
            // layer.
            if (next != null)
            {
                seenNext++;

                // NOTE(gp) not unexpectedly we have observed that 250 high
                // quality packets make 5 seconds to arrive (approx), then 250
                // low quality packets will make 10 seconds to arrive (approx),
                // If we don't take that fact into account, then the immediate
                // lower layer makes twice as much to expire.
                //
                // Assuming that each upper layer doubles the number of packets
                // it sends in a given interval, we normalize the MAX_NEXT_SEEN
                // to reflect the different relative rates of incoming packets
                // of the different simulcast layers we receive.

                if (seenNext > MAX_NEXT_SEEN * Math.pow(2, next.getOrder()))
                {
                    if (getOverride() == null)
                    {
                        this.onSimulcastLayersChanged(next);
                    }
                    else
                    {
                    }

                    this.weakCurrent = weakNext;
                    this.weakNext = null;
                }
            }
        }
    }

    public abstract boolean shouldStreamHQ();

    private void onPeerLayerChanged(SimulcastLayer layer)
    {
        if (!layer.isStreaming())
        {
            // HQ stream has stopped, switch to a lower quality stream.

            SimulcastReceiverOptions options = new SimulcastReceiverOptions();

            options.setNextOrder(SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ);
            options.setHardSwitch(true);
            options.setUrgent(true);

            configure(options);

            maybeForgetNext();
        }
        else
        {
            if (shouldStreamHQ())
            {
                SimulcastReceiverOptions options
                        = new SimulcastReceiverOptions();

                options.setNextOrder(
                        SimulcastLayer.SIMULCAST_LAYER_ORDER_HQ);
                // options.setHardSwitch(false);
                // options.setUrgent(false);

                configure(options);
            }
        }
    }

    private void onPeerLayersChanged(SimulcastSender peerSM)
    {
        if (peerSM != null && peerSM.hasLayers())
        {
            for (SimulcastLayer layer : peerSM.getSimulcastLayers())
            {
                // Add listener from the current receiving simulcast layers.
                layer.addPropertyChangeListener(weakPropertyChangeListener);
            }

            // normally getPeer() == peerSM.getVideoChannel().getEndpoint()
            // holds.

            // Initialize the receiver.
            configure(initOptions);
        }
    }

    public void propertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        if (SimulcastLayer.IS_STREAMING_PROPERTY
                .equals(propertyChangeEvent.getPropertyName()))
        {
            // A remote simulcast layer has either started or stopped
            // streaming. Deal with it.
            SimulcastLayer layer
                    = (SimulcastLayer) propertyChangeEvent.getSource();

            onPeerLayerChanged(layer);
        }
        else if (SimulcastLayer.SIMULCAST_LAYERS_PROPERTY.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // The simulcast layers of the peer have changed, (re)attach.
            SimulcastSender peerSM
                    = (SimulcastSender) propertyChangeEvent.getSource();

            onPeerLayersChanged(peerSM);
        }
    }
}
