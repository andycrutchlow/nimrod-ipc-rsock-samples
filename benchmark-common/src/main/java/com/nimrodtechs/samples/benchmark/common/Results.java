package com.nimrodtechs.samples.benchmark.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;

public final class Results {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private Results() { }
    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path tmp=path.resolveSibling(path.getFileName()+".tmp"); JSON.writeValue(tmp.toFile(),value);
        Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);
    }
    public static Map<String,Object> provenance() throws Exception {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("javaVersion",System.getProperty("java.runtime.version")); m.put("javaVendor",System.getProperty("java.vendor"));
        m.put("os",System.getProperty("os.name"));m.put("osVersion",System.getProperty("os.version"));
        m.put("architecture",System.getProperty("os.arch"));m.put("availableProcessors",Runtime.getRuntime().availableProcessors());
        m.put("jvmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        List<Map<String,Object>> artifacts=new ArrayList<>();
        for(String entry:System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path p=Path.of(entry); if(!Files.isRegularFile(p)) continue;
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(p)) { byte[] buf=new byte[65536]; int n; while((n=in.read(buf))!=-1) md.update(buf,0,n); }
            artifacts.add(Map.of("path",p.toAbsolutePath().toString(),"sha256",HexFormat.of().formatHex(md.digest())));
        }
        m.put("classpathArtifacts",artifacts);return m;
    }
}
