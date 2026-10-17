package com.nimrodtechs.samples.benchmark.dubbo;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.config.*;
import org.springframework.core.env.Environment;
import java.util.*;

public final class DubboSettings {
    private DubboSettings() { }
    /** Used once by the local supervisor: Dubbo2 rewrites loopback targets to this interface. */
    public static void main(String[] args) { System.out.println("BENCHMARK_HOST="+org.apache.dubbo.common.utils.NetUtils.getLocalHost()); }
    public static ApplicationConfig application(String role) {
        ApplicationConfig app=new ApplicationConfig("ipc-benchmark-"+role);app.setQosEnable(false);return app;
    }
    public static RegistryConfig registry() { return new RegistryConfig("N/A"); }
    public static Map<String,Object> identity(Environment env) {
        Serialization serializer=ExtensionLoader.getExtensionLoader(Serialization.class).getExtension("kryo");
        if(serializer instanceof ObservedSerialization observed) serializer=observed.delegate();
        if(!serializer.getClass().getName().toLowerCase(Locale.ROOT).contains("kryo"))throw new IllegalStateException("Unexpected serializer "+serializer.getClass());
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("library","dubbo2");m.put("version",org.apache.dubbo.common.Version.getVersion());m.put("protocol","dubbo-binary-tcp");
        m.put("serializerRequested","kryo");m.put("serializerClass",serializer.getClass().getName());m.put("serializerWireId",serializer.getContentTypeId());
        m.put("serializerExtensionVersion","bundled dubbo-serialization-kryo 2.7.23");m.put("kryoVersion","4.0.2");m.put("kryoSerializersVersion","0.42");m.put("connections",1);m.put("connectionMode","reference-private, multiplexed");
        m.put("registry","N/A");m.put("injvm",false);m.put("lazy",false);m.put("retries",0);m.put("cluster","failfast");m.put("timeoutMillis",env.getProperty("benchmark.timeout-millis",Integer.class,5000));
        m.put("url",url(env));m.put("serverWorkers",8);m.put("serverQueueCapacity",32);
        m.put("nativeClientDeadline",true);m.put("heartbeatMillis",60000);m.put("heartbeatTimeoutMillis",180000);m.put("connectTimeoutMillis",3000);m.put("reconnectMillis",2000);
        m.put("tcpNoDelay",true);m.put("keepalive",true);m.put("serializerRegistration","extension default classes; benchmark DTO not explicitly registered");
        return m;
    }
    public static String url(Environment env) { return "dubbo://"+env.getProperty("benchmark.host","127.0.0.1")+":"+env.getProperty("benchmark.port","20880")+"/"+DubboBenchmark.class.getName()
            +"?serialization=kryo&connections=1&lazy=false&injvm=false&timeout="+env.getProperty("benchmark.timeout-millis","5000")
            +"&retries=0&cluster=failfast&connect.timeout=3000&heartbeat=60000&heartbeat.timeout=180000&reconnect=2000&tcp.nodelay=true&keepalive=true"; }
}
