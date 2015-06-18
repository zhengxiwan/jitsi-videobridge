/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast;

import java.net.*;
import java.util.*;

/**
 * @author George Politis
 */
public interface SimulcastSender
{
    boolean hasLayers();

    SortedSet<SimulcastLayer> getSimulcastLayers();

    void setSimulcastLayers(SortedSet<SimulcastLayer> simulcastLayers);

    void acceptedDataInputStreamDatagramPacket(byte[] buffer, int offset, int length);

    SimulcastLayer getSimulcastLayer(int nextOrder);
}
