package com.nimrodtechs.samples.security;

import com.nimrodtechs.samples.dto.SecurityFilterRecord;
import com.nimrodtechs.samples.rmiservices.security.SecurityFiltersRmi;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

@Service
public class SecurityFiltersService implements SecurityFiltersRmi {
    public static SecurityFilterRecord record(int index) {
        return new SecurityFilterRecord("dv-" + index, "cusip-" + index, "isin-" + index,
                "figi-" + index, "universe-" + index, "industry-" + index,
                "security-type-" + index, "market-sector-" + index);
    }
    @Override public SecurityFilterRecord getSecurityFilterRecord(int index) { return record(index); }
    @Override public SecurityFilterRecord[] getSecurityFilterArray(int count) {
        return getSecurityFilters(count).toArray(SecurityFilterRecord[]::new);
    }
    @Override public List<SecurityFilterRecord> getSecurityFilters(int count) {
        if (count < 0 || count > 50_000) throw new IllegalArgumentException("count must be between 0 and 50000");
        List<SecurityFilterRecord> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(record(i));
        return result;
    }
}
