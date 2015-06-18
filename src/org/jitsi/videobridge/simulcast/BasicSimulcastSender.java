/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.util.event.*;

import java.net.*;
import java.util.*;

/**
 * @author George Politis
 *
 * TODO move to libjitsi
 */
public class BasicSimulcastSender
        extends PropertyChangeNotifier implements SimulcastSender
{
    /**
     * The simulcast layers of this <tt>VideoChannel</tt>.
     */
    private SortedSet<SimulcastLayer> simulcastLayers;

    /**
     * Returns true if the endpoint has signaled two or more simulcast layers.
     *
     * @return
     */
    public boolean hasLayers()
    {
        SortedSet<SimulcastLayer> sl = simulcastLayers;
        return sl != null && sl.size() > 1;
    }

    /**
     *
     * @param targetOrder
     * @return
     */
    public SimulcastLayer getSimulcastLayer(int targetOrder)
    {
        SimulcastLayer next = null;

        SortedSet<SimulcastLayer> layers = getSimulcastLayers();

        if (layers != null && !layers.isEmpty())
        {
            Iterator<SimulcastLayer> it = layers.iterator();

            int currentLayer = SimulcastLayer.SIMULCAST_LAYER_ORDER_LQ;
            while (it.hasNext()
                    && currentLayer++ <= targetOrder)
            {
                next = it.next();
            }
        }

        return next;
    }

    /**
     *
     * @param buffer
     * @param offset
     * @param length
     * @return
     */
    private int readSSRC(byte[] buffer, int offset, int length)
    {
        if (length >= RTPHeader.SIZE)
        {
            int v = ((buffer[offset] & 0xc0) >>> 6);

            if (v == 2)
                return RTPTranslatorImpl.readInt(buffer, offset + 8);
        }
        return 0;
    }

    /**
     * Gets the simulcast layers of this simulcast manager.
     *
     * @return
     */
    public SortedSet<SimulcastLayer> getSimulcastLayers()
    {
        SortedSet<SimulcastLayer> sl = simulcastLayers;
        return
                (sl == null)
                        ? null
                        : new TreeSet<SimulcastLayer>(sl);
    }

    public void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers)
    {

        this.simulcastLayers = simulcastLayers;

        // FIXME(gp) use an event dispatcher or a thread pool.
        new Thread(new Runnable()
        {
            public void run()
            {
                firePropertyChange(SimulcastLayer.SIMULCAST_LAYERS_PROPERTY, null, null);
            }
        }).start();
    }

    /**
     * Notifies this instance that a <tt>DatagramPacket</tt> packet received on
     * the data <tt>DatagramSocket</tt> of this <tt>Channel</tt> has been
     * accepted for further processing within Jitsi Videobridge.
     *
     */
    public void acceptedDataInputStreamDatagramPacket(byte[] buffer, int offset, int length)
    {
        // With native simulcast we don't have a notification when a stream
        // has started/stopped. The simulcast manager implements a timeout
        // for the high quality stream and it needs to be notified when
        // the channel has accepted a datagram packet for the timeout to
        // function correctly.

        if (hasLayers() && buffer != null && buffer.length != 0)
        {
            int acceptedSSRC = readSSRC(buffer, offset, length);

            SortedSet<SimulcastLayer> layers = null;
            SimulcastLayer acceptedLayer = null;

            if (acceptedSSRC != 0)
            {
                layers = getSimulcastLayers();

                // Find the accepted layer.
                for (SimulcastLayer layer : layers)
                {
                    if ((int) layer.getPrimarySSRC() == acceptedSSRC)
                    {
                        acceptedLayer = layer;
                        break;
                    }
                }
            }

            // If this is not an RTP packet or if we can't find an accepted
            // layer, log and return as this situation makes no sense.
            if (acceptedLayer == null)
            {
                return;
            }

            acceptedLayer.acceptedDataInputStreamDatagramPacket(length);

            // NOTE(gp) we expect the base layer to be always on, so we never
            // touch it or starve it.

            if (acceptedLayer == layers.first())
            {
                // We have accepted a base layer packet, starve the higher
                // quality layers.
                for (SimulcastLayer layer : layers)
                {
                    if (acceptedLayer != layer)
                    {
                        layer.maybeTimeout();
                    }
                }
            }
            else
            {
                // We have accepted a non-base layer packet, touch the accepted
                // layer.
                acceptedLayer.touch();
            }
        }
    }
}
