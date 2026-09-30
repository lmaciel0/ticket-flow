package com.ticketflow.sla;

import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BusinessHoursConfig {

    /** Only exists when app.sla.business-hours.enabled=true; otherwise SlaCalculator counts 24/7. */
    @Bean
    @ConditionalOnProperty(name = "app.sla.business-hours.enabled", havingValue = "true")
    BusinessCalendar businessCalendar(BusinessHoursProperties properties, @Value("${app.zone}") ZoneId zone) {
        return new BusinessCalendar(zone, properties.start(), properties.end(), properties.days(),
                properties.holidays());
    }
}
