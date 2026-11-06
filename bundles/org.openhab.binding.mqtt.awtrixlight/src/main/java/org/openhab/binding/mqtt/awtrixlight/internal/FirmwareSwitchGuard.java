/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.awtrixlight.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingUID;

/**
 * The {@link FirmwareSwitchGuard} remembers when a clock changed to the other firmware type. A handler is replaced
 * by such a change, so it cannot keep this itself. Without the guard, a signal of the firmware that is not running
 * any more (for example a retained message on the broker) could switch the clock back and forth.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class FirmwareSwitchGuard {

    static final Duration HOLD_OFF = Duration.ofMinutes(5);

    private final Clock clock;
    private final Map<ThingUID, Instant> lastSwitch = new HashMap<>();

    public FirmwareSwitchGuard() {
        this(Clock.systemUTC());
    }

    FirmwareSwitchGuard(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records the switch of the clock if it is allowed.
     *
     * @return {@code true} if the clock may change its firmware type now, {@code false} if it did so just before
     */
    public synchronized boolean tryRecordSwitch(ThingUID clockUid) {
        Instant now = clock.instant();
        Instant last = lastSwitch.get(clockUid);
        if (last != null && Duration.between(last, now).compareTo(HOLD_OFF) < 0) {
            return false;
        }
        lastSwitch.put(clockUid, now);
        return true;
    }

    /**
     * @return how long the clock has to wait until it may change its firmware type, {@link Duration#ZERO} if it may
     *         do so now
     */
    public synchronized Duration holdOffRemaining(ThingUID clockUid) {
        Instant last = lastSwitch.get(clockUid);
        if (last == null) {
            return Duration.ZERO;
        }
        Duration remaining = HOLD_OFF.minus(Duration.between(last, clock.instant()));
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
