package com.nimrodtechs.samples.rmiservices.security;

import com.nimrodtechs.ipcrsock.annotations.NimrodRmiInterface;
import com.nimrodtechs.samples.dto.SecurityFilterRecord;
import java.util.List;

/** The processor supplies Mono-returning @MessageMapping server handlers. */
@NimrodRmiInterface(serviceName = "server1")
public interface SecurityFiltersRmi {
    SecurityFilterRecord getSecurityFilterRecord(int index) throws Exception;
    SecurityFilterRecord[] getSecurityFilterArray(int count) throws Exception;
    List<SecurityFilterRecord> getSecurityFilters(int count) throws Exception;
}
