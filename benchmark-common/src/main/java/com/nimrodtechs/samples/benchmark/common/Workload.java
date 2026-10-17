package com.nimrodtechs.samples.benchmark.common;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.zip.CRC32C;

public final class Workload {
    public static final String PRICE="1.1234";
    private Workload() { }
    public static byte[] payload(int bytes, long seed) {
        byte[] data=new byte[bytes]; new Random(seed).nextBytes(data); return data;
    }
    public static long checksum(byte[] data) { CRC32C crc=new CRC32C(); crc.update(data); return crc.getValue(); }
    public static BenchmarkMessage request(String run, String phase, long sequence, byte[] data, long checksum) {
        BenchmarkMessage m=new BenchmarkMessage();
        m.setRunId(run); m.setPhaseId(phase); m.setSequence(sequence); m.setPayload(data);
        m.setChecksum(checksum); m.setCcyPair("EUR/USD"); m.setTenor("SPOT"); m.setPrice(""); return m;
    }
    public static BenchmarkMessage process(BenchmarkMessage request) {
        if(request.getPayload()==null || request.getPayload().length>102400 || checksum(request.getPayload())!=request.getChecksum()
                || !"EUR/USD".equals(request.getCcyPair()) || !"SPOT".equals(request.getTenor()))
            throw new IllegalArgumentException("Invalid benchmark request");
        BenchmarkMessage response=request(request.getRunId(),request.getPhaseId(),request.getSequence(),request.getPayload(),request.getChecksum());
        response.setPrice(PRICE); return response;
    }
    public static void validate(BenchmarkMessage request, BenchmarkMessage response) {
        if(response==null || !Objects.equals(request.getRunId(),response.getRunId())
                || !Objects.equals(request.getPhaseId(),response.getPhaseId()) || request.getSequence()!=response.getSequence()
                || !Objects.equals(request.getCcyPair(),response.getCcyPair()) || !Objects.equals(request.getTenor(),response.getTenor())
                || !PRICE.equals(response.getPrice()) || request.getChecksum()!=response.getChecksum()
                || response.getPayload()==null || checksum(response.getPayload())!=request.getChecksum()
                || !Arrays.equals(request.getPayload(),response.getPayload())) throw new InvalidResponseException();
    }
    public static final class InvalidResponseException extends RuntimeException {
        public InvalidResponseException() { super("Response identity, content or checksum mismatch"); }
    }
}
