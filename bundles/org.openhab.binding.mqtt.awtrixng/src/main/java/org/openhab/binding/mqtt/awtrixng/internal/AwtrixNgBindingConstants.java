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
package org.openhab.binding.mqtt.awtrixng.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.mqtt.MqttBindingConstants;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link AwtrixNgBindingConstants} class defines common constants, which are used across the whole binding.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class AwtrixNgBindingConstants {

    public static final String BINDING_ID = MqttBindingConstants.BINDING_ID;

    public static final ThingTypeUID THING_TYPE_CLOCK = new ThingTypeUID(BINDING_ID, "awtrixng-clock");
    public static final ThingTypeUID THING_TYPE_APP = new ThingTypeUID(BINDING_ID, "awtrixng-app");

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_CLOCK, THING_TYPE_APP);
}
