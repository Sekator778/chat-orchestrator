package com.example.telegramuserbot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

/**
 * Logback TurboFilter that suppresses known non-fatal TDLib errors.
 *
 * <p>This filter intercepts ALL log events before they reach any appender,
 * making it effective for both file and console output.</p>
 *
 * <p>Filtered errors (DENY):</p>
 * <ul>
 *   <li>"dialog date didn't increase" - TDLib pagination state error, cosmetic only</li>
 *   <li>"Last server dialog date didn't increased" - Same error, different wording</li>
 * </ul>
 *
 * <p>Usage: Add to logback-spring.xml as a turboFilter.</p>
 */
public final class TdLibErrorFilter extends TurboFilter {

    private static final String TDLIB_LOGGER_NAME = "it.tdlight.TDLight";

    private static final String[] FILTERED_PATTERNS = {
        "dialog date didn't increase",
        "last server dialog date didn't increased"
    };

    @Override
    public FilterReply decide(
            Marker marker,
            Logger logger,
            Level level,
            String format,
            Object[] params,
            Throwable throwable) {

        if (logger == null || format == null) {
            return FilterReply.NEUTRAL;
        }
        if (!TDLIB_LOGGER_NAME.equals(logger.getName())) {
            return FilterReply.NEUTRAL;
        }
        if (level != Level.ERROR) {
            return FilterReply.NEUTRAL;
        }
        String message = format.toLowerCase();
        for (String pattern : FILTERED_PATTERNS) {
            if (message.contains(pattern)) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }
}
