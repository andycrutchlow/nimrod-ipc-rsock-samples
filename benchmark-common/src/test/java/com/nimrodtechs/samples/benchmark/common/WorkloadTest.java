package com.nimrodtechs.samples.benchmark.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadTest {
    @Test void fullBodyRoundTripsAllSizesAndRejectsCorruption() {
        for(int size:new int[]{128,1024,10240,102400}) {
            byte[] data=Workload.payload(size,42);
            var request=Workload.request("run","measurement",7,data,Workload.checksum(data));
            var response=Workload.process(request);
            assertDoesNotThrow(()->Workload.validate(request,response));
            response.setSequence(8);
            assertThrows(Workload.InvalidResponseException.class,()->Workload.validate(request,response));
            response.setSequence(7);response.setPayload(data.clone());response.getPayload()[size-1]^=1;
            assertThrows(Workload.InvalidResponseException.class,()->Workload.validate(request,response));
        }
    }
    @Test void checksumMismatchIsNotProcessed() {
        var request=Workload.request("run","measurement",1,new byte[128],123);
        assertThrows(IllegalArgumentException.class,()->Workload.process(request));
    }
}
