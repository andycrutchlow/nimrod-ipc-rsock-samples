package com.nimrodtechs.samples.dto;

import java.math.BigDecimal;

public class PriceResponse {
    private String ccyPair;
    private String tenor;
    private BigDecimal price;
    private long timeSent;
    private long timeResponded;
    private long timeReceived;
    public PriceResponse(){}

    public PriceResponse(String s, String tenor, BigDecimal bigDecimal, long timeSent, long timeResponded) {
        this.ccyPair = s;
        this.tenor = tenor;
        this.price = bigDecimal;
        this.timeSent = timeSent;
        this.timeResponded = timeResponded;
    }

    public String getCcyPair() {
        return ccyPair;
    }
    public void setCcyPair(String ccyPair) {
        this.ccyPair = ccyPair;
    }
    public String getTenor() {
        return tenor;
    }
    public void setTenor(String tenor) {
        this.tenor = tenor;
    }
    public BigDecimal getPrice() {
        return price;
    }
    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    public long getTimeSent() {
        return timeSent;
    }
    public void setTimeSent(long timeSent) {
        this.timeSent = timeSent;
    }
    public long getTimeResponded() {
        return timeResponded;
    }
    public void setTimeResponded(long timeResponded) {
        this.timeResponded = timeResponded;
    }
    public long getTimeReceived() {
        return timeReceived;
    }
    public void setTimeReceived(long timeReceived) {
        this.timeReceived = timeReceived;
    }

}
