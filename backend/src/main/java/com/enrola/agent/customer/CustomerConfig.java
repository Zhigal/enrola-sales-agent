package com.enrola.agent.customer;

import java.time.ZoneId;

public record CustomerConfig(
        String id,
        String agentName,
        String calendlyEventId,
        ZoneId timezone,
        int smsCharLimit,
        ReloadableFile prompt,
        ReloadableFile infoPack) {}
