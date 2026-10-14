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
package org.openhab.binding.mqtt.awtrixng.internal.dto;

/**
 * An entry published on {@code <prefix>/event/error} when a command failed.
 *
 * @author Keith T. Garner - Initial contribution
 */
public class ErrorEvent {
    public static class Error {
        public String code = "";
        public String message = "";
        public String field = "";
    }

    public String source = "";
    public String request = "";
    public Error error = new Error();
}
