package com.nimrodtechs.samples.benchmark.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Dubbo SPI decorator: delegates unchanged wire encoding and records the actually invoked codec. */
public final class ObservedSerialization implements Serialization {
    private static final Map<String,Counts> COUNTS=new ConcurrentHashMap<>();
    private final Serialization delegate;
    public ObservedSerialization(Serialization delegate) { this.delegate=delegate; }
    public Serialization delegate() { return delegate; }
    @Override public byte getContentTypeId() { return delegate.getContentTypeId(); }
    @Override public String getContentType() { return delegate.getContentType(); }
    private Counts counts(URL url) {
        if(!"dubbo".equals(url.getProtocol()))throw new IllegalStateException("Non-native Dubbo protocol: "+url);
        if(!delegate.getClass().getName().equals("org.apache.dubbo.common.serialize.kryo.KryoSerialization"))
            throw new IllegalStateException("Unexpected serializer: "+delegate.getClass());
        return COUNTS.computeIfAbsent(delegate.getClass().getName(),k->new Counts(delegate.getContentTypeId(),url.toFullString(),delegate.getClass().getProtectionDomain().getCodeSource().getLocation().toString()));
    }
    @Override public ObjectOutput serialize(URL url,OutputStream output) throws IOException {
        var result=delegate.serialize(url,output);counts(url).encoded.increment();return result;
    }
    @Override public ObjectInput deserialize(URL url,InputStream input) throws IOException {
        var result=delegate.deserialize(url,input);counts(url).decoded.increment();return result;
    }
    public static Map<String,Object> snapshot() {
        Map<String,Object> codecs=new LinkedHashMap<>();
        COUNTS.forEach((name,count)->codecs.put(name,Map.of("serializeCalls",count.encoded.sum(),"deserializeCalls",count.decoded.sum(),"wireSerializerId",count.id,"effectiveNativeUrl",count.url,"serializerCodeSource",count.codeSource)));
        return Map.of("observedCodecs",codecs,"protocol","dubbo","encodingChanged",false,"instrumentation","two LongAdder counters per RPC; included in Dubbo workload");
    }
    private static final class Counts {
        final int id;final String url,codeSource;final LongAdder encoded=new LongAdder(),decoded=new LongAdder();
        Counts(int id,String url,String codeSource) { this.id=id;this.url=url;this.codeSource=codeSource; }
    }
}
