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

import java.util.*;

import net.sf.fmj.media.rtp.*;

import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.videobridge.*;

/**
 * The simulcast manager of a <tt>VideoChannel</tt>.
 *
 * @author George Politis
 */
public class SimulcastManager
{
    /**
     *
     */
    private BridgeSimulcastSender simulcastSender
            = new BridgeSimulcastSender(this);

    /**
     * Associates sending endpoints to receiving simulcast layer. This simulcast
     * manager uses this map to determine whether or not to forward a video RTP
     * packet to its associated endpoint or not.  An entry in a this map will
     * automatically be removed when its key is no longer in ordinary use.
     *
     * <tt>SimulcastReceiver</tt>s
     */
    private final Map<BridgeSimulcastSender, BridgeSimulcastReceiver>
            simulcastReceivers
            = new WeakHashMap<BridgeSimulcastSender, BridgeSimulcastReceiver>();

    /**
     * The associated <tt>VideoChannel</tt> of this simulcast manager.
     */
    private final VideoChannel videoChannel;

    public SimulcastManager(VideoChannel videoChannel)
    {
        this.videoChannel = videoChannel;
    }

    /**
     *
     * @return
     */
    public BridgeSimulcastSender getSimulcastSender()
    {
        return simulcastSender;
    }

    /**
     * Determines whether the packet belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @return
     */
    public boolean accept(RTPPacket pkt, BridgeSimulcastSender peerSM)
    {
        return pkt == null ? false : accept(pkt.ssrc & 0xffffl, peerSM);
    }

    /**
     * Determines whether the packet belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @return
     */
    public boolean accept(
            byte[] buffer, int offset, int length,
            BridgeSimulcastSender peerSM)
    {
        boolean accept = true;

        if (peerSM != null && peerSM.hasLayers())
        {
            // FIXME(gp) inconsistent usage of longs and ints.

            // Get the SSRC of the packet.
            long ssrc = readSSRC(buffer, offset, length) & 0xffffffffl;

            if (ssrc > 0)
                accept = accept(ssrc, peerSM);
        }

        return accept;
    }

    /**
     * Determines whether the SSRC belongs to a simulcast substream that is
     * being received by the <tt>Channel</tt> associated to this simulcast
     * manager.
     *
     * @param ssrc
     * @param peerSM
     * @return
     */
    public boolean accept(long ssrc, BridgeSimulcastSender peerSM)
    {
        boolean accept = true;

        if (ssrc > 0 && peerSM != null && peerSM.hasLayers())
        {
            BridgeSimulcastReceiver sr = getOrCreateSimulcastReceiver(peerSM);

            if (sr != null)
                accept = sr.accept(ssrc);
        }

        return accept;
    }

    /**
     * .
     * @param peerSM
     * @return
     */
    public long getIncomingBitrate(SimulcastManager peerSM, boolean noOverride)
    {
        long bitrate = 0;

        if (peerSM == null || !peerSM.getSimulcastSender().hasLayers())
        {
            return bitrate;
        }

        BridgeSimulcastReceiver sr = getOrCreateSimulcastReceiver(peerSM.getSimulcastSender());
        if (sr != null)
        {
            bitrate = sr.getIncomingBitrate(noOverride);
        }

        return bitrate;
    }

    /**
     * Determines which simulcast layer from the srcVideoChannel is currently
     * being received by this video channel.
     *
     * @param simulcastSender
     * @return
     */
    public BridgeSimulcastReceiver getOrCreateSimulcastReceiver(
            BridgeSimulcastSender simulcastSender)
    {
        BridgeSimulcastReceiver sr = null;

        if (simulcastSender != null && simulcastSender.hasLayers())
        {
            synchronized (simulcastReceivers)
            {
                if (!simulcastReceivers.containsKey(simulcastSender))
                {
                    // Create a new receiver.
                    sr = new BridgeSimulcastReceiver(this, simulcastSender);
                    simulcastReceivers.put(simulcastSender, sr);
                }
                else
                {
                    // Get the receiver that handles this peer simulcast manager
                    sr = simulcastReceivers.get(simulcastSender);
                }
            }
        }

        return sr;
    }

    public VideoChannel getVideoChannel()
    {
        return videoChannel;
    }

    public boolean override(int overrideOrder)
    {
        synchronized (simulcastReceivers)
        {
            Integer oldOverrideOrder
                = SimulcastReceiver.initOptions.getOverrideOrder();

            if (oldOverrideOrder == null
                    || oldOverrideOrder.intValue() != overrideOrder)
            {
                SimulcastReceiver.initOptions.setOverrideOrder(overrideOrder);

                if (!simulcastReceivers.isEmpty())
                {
                    SimulcastReceiverOptions options
                        = new SimulcastReceiverOptions();

                    options.setOverrideOrder(overrideOrder);
                    for (BridgeSimulcastReceiver sr : simulcastReceivers.values())
                    {
                        sr.configure(options);
                    }
                }

                return true;
            }
            else
            {
                return false;
            }
        }
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


}
