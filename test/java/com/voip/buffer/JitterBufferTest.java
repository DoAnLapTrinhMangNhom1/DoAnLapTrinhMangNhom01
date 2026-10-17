package com.voip.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit Tests for JitterBuffer based on the 5-step test specification:
 * 1. Bơm sequence lộn xộn (1,3,2,5,4) -> poll ra đúng thứ tự 1,2,3,4,5.
 * 2. Test gói đến muộn: đang ở seq 10, đưa gói 8 vào -> bị drop.
 * 3. Test mất gói: bơm 1,2, 4,5 -> seq 3 bị thiếu -> poll() ra mảng 0 (silence) hoặc drop frame.
 * 4. Thread test: 1 thread put 1000 gói, 1 thread poll -> không crash / dead-lock.
 * 5. Cập nhật Stats: đưa 100 gói -> getStats() thấy đúng số gói nhận, tỷ lệ rớt.
 */
public class JitterBufferTest {

    private JitterBuffer buffer;

    @BeforeEach
    public void setUp() {
        buffer = new JitterBuffer(50); // 50 ms delay
    }

    private byte[] createDummyPayload(int seq) {
        byte[] payload = new byte[JitterBuffer.DEFAULT_FRAME_SIZE];
        // Fill first bytes with sequence info for identification
        payload[0] = (byte) ((seq >> 8) & 0xFF);
        payload[1] = (byte) (seq & 0xFF);
        return payload;
    }

    private int extractSeqFromPayload(byte[] payload) {
        if (payload == null || payload.length < 2) return -1;
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    private boolean isSilence(byte[] payload) {
        if (payload == null) return false;
        for (byte b : payload) {
            if (b != 0) return false;
        }
        return true;
    }

    /**
     * Step 1: Bơm sequence lộn xộn (1, 3, 2, 5, 4) -> poll ra đúng thứ tự 1, 2, 3, 4, 5.
     */
    @Test
    public void test1_ReorderDisorderedPackets() {
        System.out.println("--- Test 1: Reordering out-of-order packets ---");
        int[] disordered = {1, 3, 2, 5, 4};

        for (int seq : disordered) {
            buffer.put(new RTPPacket(seq, createDummyPayload(seq)));
        }

        // Buffer has 5 packets, threshold for 50ms is 3 packets. Buffering is complete.
        for (int expected = 1; expected <= 5; expected++) {
            byte[] frame = buffer.poll();
            assertNotNull(frame, "Polled frame for seq " + expected + " should not be null");
            assertEquals(640, frame.length);
            int seqInPayload = extractSeqFromPayload(frame);
            assertEquals(expected, seqInPayload, "Expected sequence " + expected + " but got " + seqInPayload);
            System.out.println("Polled successfully in order: seq = " + seqInPayload);
        }

        // Queue should now be empty
        assertNull(buffer.poll(), "Queue should be empty after polling all packets");
        System.out.println("Test 1 PASSED: Packets polled in exact order 1, 2, 3, 4, 5.\n");
    }

    /**
     * Step 2: Test gói đến muộn: đang ở seq 10, đưa gói 8 vào -> bị drop.
     */
    @Test
    public void test2_LatePacketDropped() {
        System.out.println("--- Test 2: Late packet dropped ---");

        // Simulate playing packets up to seq 10
        for (int i = 1; i <= 10; i++) {
            buffer.put(new RTPPacket(i, createDummyPayload(i)));
        }
        for (int i = 1; i <= 10; i++) {
            byte[] frame = buffer.poll();
            assertNotNull(frame);
            assertEquals(i, extractSeqFromPayload(frame));
        }

        assertEquals(10, buffer.getLastPlayedSeq(), "Playback head should be at seq 10");

        long droppedBefore = buffer.getStats().getPacketsDropped();
        int sizeBefore = buffer.size();

        // Now pump late packet with seq = 8
        buffer.put(new RTPPacket(8, createDummyPayload(8)));

        // Verify seq 8 was dropped and not inserted into buffer
        assertEquals(sizeBefore, buffer.size(), "Late packet must not be added to buffer");
        assertEquals(droppedBefore + 1, buffer.getStats().getPacketsDropped(), "Dropped counter must increment");

        // Poll should return null because buffer is empty
        assertNull(buffer.poll(), "Poll should return null as no valid packets exist");
        System.out.println("Test 2 PASSED: Late packet seq 8 correctly dropped while at seq 10.\n");
    }

    /**
     * Step 3: Test mất gói: bơm 1, 2, 4, 5 -> seq 3 bị thiếu -> poll() ra mảng 0 (silence) hoặc drop frame.
     */
    @Test
    public void test3_MissingPacketConcealment() {
        System.out.println("--- Test 3: Missing packet concealment with silence ---");

        int[] input = {1, 2, 4, 5}; // Seq 3 is missing
        for (int seq : input) {
            buffer.put(new RTPPacket(seq, createDummyPayload(seq)));
        }

        // 1. Poll seq 1
        byte[] frame1 = buffer.poll();
        assertNotNull(frame1);
        assertEquals(1, extractSeqFromPayload(frame1));

        // 2. Poll seq 2
        byte[] frame2 = buffer.poll();
        assertNotNull(frame2);
        assertEquals(2, extractSeqFromPayload(frame2));

        // 3. Poll seq 3 (missing) -> must return silence frame of all 0s
        byte[] frame3 = buffer.poll();
        assertNotNull(frame3, "Concealment frame should not be null");
        assertEquals(640, frame3.length);
        assertTrue(isSilence(frame3), "Missing packet seq 3 must produce silence frame (all 0s)");
        System.out.println("Seq 3 missing -> Successfully generated silence concealment frame.");

        // 4. Poll seq 4
        byte[] frame4 = buffer.poll();
        assertNotNull(frame4);
        assertEquals(4, extractSeqFromPayload(frame4));

        // 5. Poll seq 5
        byte[] frame5 = buffer.poll();
        assertNotNull(frame5);
        assertEquals(5, extractSeqFromPayload(frame5));

        // 6. Drained
        assertNull(buffer.poll());

        // Check stats recorded packet loss
        StreamStats stats = buffer.getStats();
        assertTrue(stats.getPacketsLost() >= 1, "Lost packets count must be >= 1");
        System.out.println("Stats after concealment: " + stats);
        System.out.println("Test 3 PASSED: Missing packet handled with silence concealment.\n");
    }

    /**
     * Step 4: Thread test: 1 thread put 1000 gói, 1 thread poll -> không crash / dead-lock.
     */
    @Test
    public void test4_MultiThreadProducerConsumer() throws InterruptedException {
        System.out.println("--- Test 4: Multi-thread producer-consumer (1000 packets) ---");
        final int TOTAL_PACKETS = 1000;
        final AtomicInteger polledCount = new AtomicInteger(0);
        final AtomicBoolean producerFinished = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            for (int i = 1; i <= TOTAL_PACKETS; i++) {
                buffer.put(new RTPPacket(i, System.currentTimeMillis(), createDummyPayload(i)));
                if (i % 50 == 0) {
                    try {
                        Thread.sleep(1); // simulate arrival pacing
                    } catch (InterruptedException ignored) {}
                }
            }
            producerFinished.set(true);
        }, "UDP-Receiver-Thread");

        Thread consumer = new Thread(() -> {
            while (!producerFinished.get() || !buffer.isEmpty()) {
                byte[] pcm = buffer.poll();
                if (pcm != null) {
                    polledCount.incrementAndGet();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException ignored) {}
                }
            }
        }, "Speaker-Playback-Thread");

        producer.start();
        consumer.start();

        producer.join(5000);
        consumer.join(5000);

        assertFalse(producer.isAlive(), "Producer thread should have finished");
        assertFalse(consumer.isAlive(), "Consumer thread should have finished");
        assertTrue(polledCount.get() > 0, "Polled frames should be greater than 0");
        System.out.println("Polled frames: " + polledCount.get() + " / " + TOTAL_PACKETS);
        System.out.println("Test 4 PASSED: Concurrent put/poll ran smoothly without crash or deadlock.\n");
    }

    /**
     * Step 5: Cập nhật Stats: đưa 100 gói -> getStats() thấy đúng số gói nhận, tỷ lệ rớt.
     */
    @Test
    public void test5_UpdateStats() {
        System.out.println("--- Test 5: QoS Stream Statistics tracking ---");
        buffer.reset();

        // Send 100 packets, but omit 5 packets (e.g. 20, 40, 60, 80, 90) -> 95 packets sent
        int totalExpected = 100;
        int sentCount = 0;
        for (int i = 1; i <= totalExpected; i++) {
            if (i == 20 || i == 40 || i == 60 || i == 80 || i == 90) {
                continue; // drop / lost in transit
            }
            buffer.put(new RTPPacket(i, System.currentTimeMillis() - 15, createDummyPayload(i)));
            sentCount++;
        }

        assertEquals(95, sentCount);
        StreamStats stats = buffer.getStats();

        assertEquals(95, stats.getPacketsReceived(), "Packets received must be 95");
        assertEquals(5, stats.getPacketsLost(), "Packets lost must be 5");
        assertEquals(5.0, stats.getLossRate(), 0.01, "Loss rate should be 5.0%");
        assertEquals(95 * 640, stats.getBytesReceived(), "Bytes received should be 95 * 640");

        System.out.println("Stats output: " + stats);
        System.out.println("Test 5 PASSED: Stats accurately report received, lost, and loss percentage.\n");
    }

    /**
     * Standalone main method allowing direct execution inside NetBeans or console.
     */
    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("    RUNNING JITTER BUFFER VERIFICATION SUITE     ");
        System.out.println("=================================================");
        JitterBufferTest test = new JitterBufferTest();

        try {
            test.setUp();
            test.test1_ReorderDisorderedPackets();

            test.setUp();
            test.test2_LatePacketDropped();

            test.setUp();
            test.test3_MissingPacketConcealment();

            test.setUp();
            test.test4_MultiThreadProducerConsumer();

            test.setUp();
            test.test5_UpdateStats();

            System.out.println("=================================================");
            System.out.println("   ALL 5 TESTS PASSED SUCCESSFULLY! 100% OK!     ");
            System.out.println("=================================================");
        } catch (Throwable t) {
            System.err.println("TEST SUITE FAILED:");
            t.printStackTrace();
        }
    }
}
