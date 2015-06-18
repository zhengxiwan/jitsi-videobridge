/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.transform;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.impl.neomedia.transform.rtcp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * Rewrites SSRCs and sequence numbers of all SSRCs of a all
 * <tt>VideoChannel</tt>.
 *
 * Not thread safe.
 *
 * @author George Politis
 */
public class SsrcRewritingEngine implements TransformEngine
{
    /**
     * The <tt>VideoChannel</tt> this <tt>SsrcRewritingEngine</tt> is associated
     * to.
     */
    private final VideoChannel myVideoChannel;

    /**
     * A <tt>Map</tt> that maps <tt>VideoChannel</tt>s to
     * <tt>SsrcRewriter</tt>s.
     */
    private Map<VideoChannel, SsrcRewriter> peerChannelEngines
            = new WeakHashMap<VideoChannel, SsrcRewriter>();

    private Object peerChannelEnginesSyncRoot = new Object();

    /**
     * The <tt>PacketTransformer</tt> that transforms <tt>RTPPacket</tt>s for
     * this <tt>TransformEngine</tt>.
     */
    private final SingleRTPPacketTransformer rtpTransformer
            = new SsrcRewritingRTPTransformer();

    /**
     * The <tt>PacketTransformer</tt> that transforms
     * <tt>RTCPCompoundPacket</tt>s for this <tt>TransformEngine</tt>.
     */
    private final SingleRTCPPacketTransformer rtcpTransformer
            = new SsrcRewritingRTCPTransformer();

    /**
     *
     */
    class SsrcRewritingRTPTransformer extends SingleRTPPacketTransformer
    {
        @Override
        public RTPPacket transform(RTPPacket pkt)
        {
            Conference conference = myVideoChannel.getContent().getConference();
            if (conference == null)
            {
                // what?
                return pkt;
            }

            Channel channel = conference
                    .findChannelByReceiveSSRC(pkt.ssrc & 0xffffffffl,
                            MediaType.VIDEO);

            if (channel == null || !(channel instanceof VideoChannel))
            {
                // what?
                return pkt;
            }

            VideoChannel peerVideoChannel = (VideoChannel) channel;

            SsrcRewriter ssrcRewriter
                    = getOrCreatePeerChannelEngine(peerVideoChannel);

            return ssrcRewriter.transform(pkt);
        }

        @Override
        public RTPPacket reverseTransform(RTPPacket pkt)
        {
            // Pass through.
            return pkt;
        }
    };

    /**
     *
     */
    class SsrcRewritingRTCPTransformer extends SingleRTCPPacketTransformer
    {
        @Override
        public RTCPCompoundPacket transform(RTCPCompoundPacket pkt)
        {
            return pkt;
        }

        @Override
        public RTCPCompoundPacket reverseTransform(RTCPCompoundPacket pkt)
        {
            return pkt;
        }
    };

    /**
     *
     * @param enabled
     */
    public void setEnabled(boolean enabled)
    {
        // rtpTransformer.setEnabled(enabled);
        // rtcpTransformer.setEnabled(enabled);
    }

    private SsrcRewriter getOrCreatePeerChannelEngine(VideoChannel peerVideoChannel)
    {
        synchronized (peerChannelEnginesSyncRoot)
        {
            if (!peerChannelEngines.containsKey(peerVideoChannel))
            {
                peerChannelEngines.put(peerVideoChannel,
                        new SsrcRewriter());
            }

            SsrcRewriter ssrcRewriter
                    = peerChannelEngines.get(peerVideoChannel);

            // todo this is the sole tie with simulcast. Ideally this should be
            // done outside this class.
            ssrcRewriter.setCurrentRewriteSsrc(
                    peerVideoChannel.getSimulcastManager()
                            .getSimulcastSender().getSimulcastLayers().first().getPrimarySSRC());

            return ssrcRewriter;
        }
    }

    /**
     *
     * @return
     */
    public boolean isEnabled()
    {
        return rtpTransformer.isEnabled();
    }

    /**
     * Ctor.
     *
     * @param myVideoChannel
     */
    public SsrcRewritingEngine(VideoChannel myVideoChannel)
    {
        // Start disabled.
        this.rtpTransformer.setEnabled(false);
        this.rtcpTransformer.setEnabled(false);

        this.myVideoChannel = myVideoChannel;
    }

    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }


    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }
}
