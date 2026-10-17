package com.nimrodtechs.samples.grpc.benchmark;

import com.google.protobuf.ByteString;
import com.nimrodtechs.samples.benchmark.common.BenchmarkMessage;

public final class BenchmarkMapping {
    private BenchmarkMapping() { }
    public static RpcMessage toWire(BenchmarkMessage m) {
        return RpcMessage.newBuilder().setRunId(m.getRunId()).setPhaseId(m.getPhaseId()).setSequence(m.getSequence())
            .setCcyPair(m.getCcyPair()).setTenor(m.getTenor()).setPrice(m.getPrice()).setChecksum(m.getChecksum())
            .setPayload(ByteString.copyFrom(m.getPayload())).build();
    }
    public static BenchmarkMessage fromWire(RpcMessage m) {
        BenchmarkMessage dto=new BenchmarkMessage();dto.setRunId(m.getRunId());dto.setPhaseId(m.getPhaseId());dto.setSequence(m.getSequence());
        dto.setCcyPair(m.getCcyPair());dto.setTenor(m.getTenor());dto.setPrice(m.getPrice());dto.setChecksum(m.getChecksum());dto.setPayload(m.getPayload().toByteArray());return dto;
    }
}
