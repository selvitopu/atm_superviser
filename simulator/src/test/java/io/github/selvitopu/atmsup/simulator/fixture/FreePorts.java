package io.github.selvitopu.atmsup.simulator.fixture;

import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/** Finds free UDP ports for tests that need to know an agent's port before it starts. */
public final class FreePorts {

    private FreePorts() {
    }

    /** Returns {@code n} distinct UDP ports that were free a moment ago (all held open together, then released). */
    public static int[] udp(int n) {
        List<DatagramSocket> sockets = new ArrayList<>(n);
        try {
            for (int i = 0; i < n; i++) {
                sockets.add(new DatagramSocket(0));
            }
            return sockets.stream().mapToInt(DatagramSocket::getLocalPort).toArray();
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        } finally {
            sockets.forEach(DatagramSocket::close);
        }
    }
}
