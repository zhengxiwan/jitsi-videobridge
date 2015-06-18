/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast;

import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.jitsi.util.Logger;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.messages.*;

import java.beans.*;
import java.io.*;
import java.lang.ref.*;
import java.util.*;

/**
 * @author George Politis
 */
public class BridgeSimulcastReceiver
    extends SimulcastReceiver
{
    /**
     * The <tt>Logger</tt> used by the <tt>ReceivingLayers</tt> class and its
     * instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(BridgeSimulcastReceiver.class);

    /**
     * Helper object that <tt>SimulcastReceiver</tt> instances use to build
     * JSON messages.
     */
    private final static SimulcastMessagesMapper mapper
            = new SimulcastMessagesMapper();

    /**
     * The <tt>SimulcastManager</tt> of the parent endpoint.
     */
    private final SimulcastManager mySM;

    /**
     * The <tt>SimulcastManager</tt> of the peer endpoint.
     */
    private final WeakReference<BridgeSimulcastSender> weakPeerSM;

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

    /**
     * Ctor.
     *
     * @param mySM
     * @param peerSM
     */
    public BridgeSimulcastReceiver(SimulcastManager mySM, BridgeSimulcastSender peerSM)
    {
        super(peerSM);
        this.weakPeerSM = new WeakReference<BridgeSimulcastSender>(peerSM);
        this.mySM = mySM;

        this.initializeConfiguration();

        mySM.getVideoChannel()
                .addPropertyChangeListener(weakPropertyChangeListener);

        Endpoint self = getSelf();
        onEndpointChanged(self, null);
    }

    private SimulcastManager getPeerSM()
    {
        WeakReference<BridgeSimulcastSender> wr = this.weakPeerSM;
        SimulcastManager peerSM = (wr != null) ? wr.get().getSimulcastManager() : null;

        if (peerSM == null)
        {
            logger.warn("The peer simulcast manager is null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(
                        Arrays.toString(
                                Thread.currentThread().getStackTrace()));
            }
        }

        return peerSM;
    }

    public void askForKeyframe(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested a key frame for null layer!");
            return;
        }

        SimulcastManager peerSM = getPeerSM();
        if (peerSM == null)
        {
            logger.warn("Requested a key frame but the peer simulcast " +
                    "manager is null!");
            return;
        }

        peerSM.getVideoChannel().askForKeyframes(
                new int[]{(int) layer.getPrimarySSRC()});

        if (logger.isDebugEnabled())
        {
            Map<String, Object> map = new HashMap<String, Object>(3);
            map.put("self", getSelf());
            map.put("peer", getPeer());
            map.put("layer", layer);
            StringCompiler sc = new StringCompiler(map);

            logger.debug(sc.c("The simulcast receiver of {self.id} for " +
                    "{peer.id} has asked for a key frame for layer " +
                    "{layer.order} ({layer.primarySSRC})."));
        }
    }

    private Endpoint getPeer()
    {
        // TODO(gp) maybe add expired checks (?)
        SimulcastManager sm;
        VideoChannel vc;
        Endpoint peer;

        peer = ((sm = getPeerSM()) != null && (vc = mySM.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (peer == null)
        {
            logger.warn("Peer is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return peer;
    }

    private Endpoint getSelf()
    {
        // TODO(gp) maybe add expired checks (?)
        VideoChannel vc;
        Endpoint self;

        self = (mySM != null && (vc = mySM.getVideoChannel()) != null)
                ? vc.getEndpoint() : null;

        if (self == null)
        {
            logger.warn("Self is null!");

            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        return self;
    }

    /**
     * Maybe send a data channel command to the associated simulcast sender to
     * make it start streaming its hq stream, if it's being watched by some receiver.
     */
    private void maybeSendStartHighQualityStreamCommand()
    {
        Endpoint newEndpoint = getPeer();
        SortedSet<SimulcastLayer> newSimulcastLayers = null;

        SimulcastManager peerSM = getPeerSM();
        if (peerSM != null)
        {
            newSimulcastLayers = peerSM.getSimulcastSender().getSimulcastLayers();
        }

        SctpConnection sctpConnection;
        if (newSimulcastLayers != null
                && newSimulcastLayers.size() > 1
                /* newEndpoint != null is implied */
                && (sctpConnection = newEndpoint.getSctpConnection()) != null
                && sctpConnection.isReady()
                && !sctpConnection.isExpired())
        {
            // we have a new endpoint and it has an SCTP connection that is
            // ready and not expired. if somebody else is watching the new
            // endpoint, start its hq stream.

            boolean startHighQualityStream = false;

            for (Endpoint e
                    : mySM.getVideoChannel().getContent().getConference()
                    .getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the
                // selected endpoint changes while we're in the loop?

                if (e == newEndpoint)
                    continue;

                Endpoint eSelectedEndpoint = e.getEffectivelySelectedEndpoint();

                if (newEndpoint == eSelectedEndpoint
                        || (SimulcastLayer.SIMULCAST_LAYER_ORDER_INIT > SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ
                        && eSelectedEndpoint == null))
                {
                    // somebody is watching the new endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, start
                    // the hq stream.

                    if (logger.isDebugEnabled())
                    {
                        Map<String,Object> map = new HashMap<String,Object>(3);

                        map.put("e", e);
                        map.put("newEndpoint", newEndpoint);
                        map.put("maybe", eSelectedEndpoint == null ? "(maybe) "
                                : "");

                        StringCompiler sc
                                = new StringCompiler(map)
                                .c("{e.id} is {maybe} watching {newEndpoint.id}.");

                        logger.debug(
                                sc.toString().replaceAll("\\s+", " "));
                    }

                    startHighQualityStream = true;
                    break;
                }
            }

            if (startHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(
                        mySM.getVideoChannel().getEndpoint().getID()
                                + " notifies " + newEndpoint.getID()
                                + " to start its HQ stream.");

                SimulcastLayer hqLayer = newSimulcastLayers.last();
                StartSimulcastLayerCommand command
                        = new StartSimulcastLayerCommand(hqLayer);
                String json = mapper.toJson(command);

                try
                {
                    newEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e)
                {
                    logger.error(
                            newEndpoint.getID()
                                    + " failed to send message on data channel.",
                            e);
                }
            }
        }
    }

    /**
     * Maybe send a data channel command to he associated simulcast sender to
     * make it stop streaming its hq stream, if it's not being watched by any
     * receiver.
     */
    private void maybeSendStopHighQualityStreamCommand()
    {
        Endpoint oldEndpoint = getPeer();
        SortedSet<SimulcastLayer> oldSimulcastLayers = null;

        SimulcastManager peerSM = getPeerSM();
        if (peerSM != null)
        {
            oldSimulcastLayers = peerSM.getSimulcastSender().getSimulcastLayers();
        }

        SctpConnection sctpConnection;
        if (oldSimulcastLayers != null
                && oldSimulcastLayers.size() > 1
                /* oldEndpoint != null is implied*/
                && (sctpConnection = oldEndpoint.getSctpConnection()) != null
                && sctpConnection.isReady()
                && !sctpConnection.isExpired())
        {
            // we have an old endpoint and it has an SCTP connection that is
            // ready and not expired. if nobody else is watching the old
            // endpoint, stop its hq stream.

            boolean stopHighQualityStream = true;
            for (Endpoint e : mySM.getVideoChannel()
                    .getContent().getConference().getEndpoints())
            {
                // TODO(gp) need some synchronization here. What if the selected
                // endpoint changes while we're in the loop?

                if (oldEndpoint != e
                        && (oldEndpoint == e.getEffectivelySelectedEndpoint())
                        || e.getEffectivelySelectedEndpoint() == null)
                {
                    // somebody is watching the old endpoint or somebody has not
                    // yet signaled its selected endpoint to the bridge, don't
                    // stop the hq stream.
                    stopHighQualityStream = false;
                    break;
                }
            }

            if (stopHighQualityStream)
            {
                // TODO(gp) this assumes only a single hq stream.

                logger.debug(mySM.getVideoChannel().getEndpoint().getID() +
                        " notifies " + oldEndpoint.getID() + " to stop " +
                        "its HQ stream.");

                SimulcastLayer hqLayer = oldSimulcastLayers.last();

                StopSimulcastLayerCommand command
                        = new StopSimulcastLayerCommand(hqLayer);

                String json = mapper.toJson(command);

                try
                {
                    oldEndpoint.sendMessageOnDataChannel(json);
                }
                catch (IOException e1)
                {
                    logger.error(oldEndpoint.getID() + " failed to send " +
                            "message on data channel.", e1);
                }
            }
        }
    }

    private void onEndpointChanged(Endpoint newValue, Endpoint oldValue)
    {
        if (newValue != null)
        {
            newValue.addPropertyChangeListener(weakPropertyChangeListener);
        }
        else
        {
            logger.warn("Cannot listen on self, it's null!");
            if (logger.isDebugEnabled())
            {
                logger.debug(Arrays.toString(
                        Thread.currentThread().getStackTrace()));
            }
        }

        if (oldValue != null)
        {
            oldValue.removePropertyChangeListener(weakPropertyChangeListener);
        }
    }

    private void onSelectedEndpointChanged(
            Endpoint oldEndpoint, Endpoint newEndpoint)
    {
        // Rule 1: send an hq stream only for the selected endpoint.
        if (newEndpoint == getPeer())
        {
            this.maybeReceiveHigh();

            // Rule 1.1: if the new endpoint is being watched by any of the
            // receivers, the bridge tells it to start streaming its hq
            // stream.
            this.maybeSendStartHighQualityStreamCommand();
        }

        // Rule 2: send an lq stream only for the previously selected
        // endpoint.
        if (oldEndpoint == getPeer())
        {
            this.receiveLow();
            // Rule 2.1: if the old endpoint is not being watched by any of
            // the receivers, the bridge tells it to stop streaming its hq
            // stream.
            this.maybeSendStopHighQualityStreamCommand();
        }
    }

    @Override
    public void propertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        if (Endpoint
                .SELECTED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()) ||
                Endpoint
                        .PINNED_ENDPOINT_PROPERTY_NAME.equals(
                        propertyChangeEvent.getPropertyName()))
        {
            // endpoint == this.manager.getVideoChannel().getEndpoint() is
            // implied.

            Endpoint oldValue = (Endpoint) propertyChangeEvent.getOldValue();
            Endpoint newValue = (Endpoint) propertyChangeEvent.getNewValue();

            onSelectedEndpointChanged(oldValue, newValue);

            return;
        }
        else if (VideoChannel.ENDPOINT_PROPERTY_NAME.equals(
                propertyChangeEvent.getPropertyName()))
        {
            // Listen for property changes from self.
            Endpoint newValue = (Endpoint) propertyChangeEvent.getNewValue();
            Endpoint oldValue = (Endpoint) propertyChangeEvent.getOldValue();

            onEndpointChanged(newValue, oldValue);

            return;
        }

        super.propertyChange(propertyChangeEvent);
    }

    public boolean shouldStreamHQ()
    {
        Endpoint self = getSelf();
        Endpoint peer = getPeer();

        return (peer != null && self != null &&
                peer == self.getEffectivelySelectedEndpoint());
    }

    public void onNextSimulcastLayerStopped(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a next simulcast layer stopped " +
                    "event but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a next simulcast layer stopped event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            NextSimulcastLayerStoppedEvent ev
                    = new NextSimulcastLayerStoppedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }

    public void onSimulcastLayersChanged(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changed event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changed event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving endpoint.
            SimulcastLayersChangedEvent ev
                    = new SimulcastLayersChangedEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changed event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }

    }

    public void onSimulcastLayersChanging(SimulcastLayer layer)
    {
        if (layer == null)
        {
            logger.warn("Requested to send a simulcast layers changing event" +
                    "but layer is null!");
            return;
        }

        Endpoint self, peer;

        if ((self = getSelf()) != null && (peer = getPeer()) != null)
        {
            logger.debug("Sending a simulcast layers changing event to "
                    + self.getID() + ".");

            // XXX(gp) it'd be nice if we could remove the
            // SimulcastLayersChangedEvent event. Ideally, receivers should
            // listen for MediaStreamTrackActivity instead. Unfortunately,
            // such an event does not exist in WebRTC.

            // Receiving simulcast layers changed, create and send
            // an event through data channels to the receiving
            // endpoint.
            SimulcastLayersChangingEvent ev
                    = new SimulcastLayersChangingEvent();

            ev.endpointSimulcastLayers = new EndpointSimulcastLayer[]{
                    new EndpointSimulcastLayer(peer.getID(), layer)
            };

            String json = mapper.toJson(ev);
            try
            {
                // FIXME(gp) sendMessageOnDataChannel may silently fail to
                // send a data message. We want to be able to handle those
                // errors ourselves.
                self.sendMessageOnDataChannel(json);
            }
            catch (IOException e)
            {
                logger.error(self.getID() + " failed to send message on " +
                        "data channel.", e);
            }
        }
        else
        {
            logger.warn("Didn't send simulcast layers changing event " +
                    "because self == null || peer == null " +
                    "|| current == null");
        }
    }

    /**
     * Whether the values for the constants have been initialized or not.
     */
    private static boolean configurationInitialized = false;

    private void initializeConfiguration()
    {
        synchronized (SimulcastReceiver.class)
        {
            if (configurationInitialized)
            {
                return;
            }

            configurationInitialized = true;

            VideoChannel channel = mySM.getVideoChannel();
            ConfigurationService cfg
                    = ServiceUtils.getService(
                    channel.getBundleContext(),
                    ConfigurationService.class);

            if (cfg != null)
            {
                MAX_NEXT_SEEN = cfg.getInt(MAX_NEXT_SEEN_PNAME, MAX_NEXT_SEEN);
            }
        }
    }
}
