package com.nimrodtechs.samples.benchmark.common;

import java.io.Serializable;

/** Plain DTO deliberately usable by Kryo 4/5 and Dubbo2 without record support. */
public class BenchmarkMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String runId, phaseId, ccyPair, tenor, price;
    private long sequence, checksum;
    private byte[] payload;
    public BenchmarkMessage() { }
    public String getRunId() { return runId; }
    public void setRunId(String v) { runId=v; }
    public String getPhaseId() { return phaseId; }
    public void setPhaseId(String v) { phaseId=v; }
    public String getCcyPair() { return ccyPair; }
    public void setCcyPair(String v) { ccyPair=v; }
    public String getTenor() { return tenor; }
    public void setTenor(String v) { tenor=v; }
    public String getPrice() { return price; }
    public void setPrice(String v) { price=v; }
    public long getSequence() { return sequence; }
    public void setSequence(long v) { sequence=v; }
    public long getChecksum() { return checksum; }
    public void setChecksum(long v) { checksum=v; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] v) { payload=v; }
}
