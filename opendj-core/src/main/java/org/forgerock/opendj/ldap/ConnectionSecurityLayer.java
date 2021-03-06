/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2010 Sun Microsystems, Inc.
 * Portions Copyright 2012-2014 ForgeRock AS.
 */

package org.forgerock.opendj.ldap;

/**
 * An interface for providing additional connection security to a connection.
 */
public interface ConnectionSecurityLayer {

    /**
     * Disposes of any system resources or security-sensitive information that
     * this connection security layer might be using. Invoking this method
     * invalidates this instance.
     */
    void dispose();

    /**
     * Unwraps a byte array received from the peer.
     *
     * @param incoming
     *            A non-{@code null} byte array containing the encoded bytes
     *            from the peer.
     * @param offset
     *            The starting position in {@code incoming} of the bytes to be
     *            unwrapped.
     * @param len
     *            The number of bytes from {@code incoming} to be unwrapped.
     * @return A non-{@code null} byte array containing the unwrapped bytes.
     * @throws LdapException
     *             If {@code incoming} cannot be successfully unwrapped.
     */
    byte[] unwrap(byte[] incoming, int offset, int len) throws LdapException;

    /**
     * Wraps a byte array to be sent to the peer.
     *
     * @param outgoing
     *            A non-{@code null} byte array containing the unencoded bytes
     *            to be sent to the peer.
     * @param offset
     *            The starting position in {@code outgoing} of the bytes to be
     *            wrapped.
     * @param len
     *            The number of bytes from {@code outgoing} to be wrapped.
     * @return A non-{@code null} byte array containing the wrapped bytes.
     * @throws LdapException
     *             If {@code outgoing} cannot be successfully wrapped.
     */
    byte[] wrap(byte[] outgoing, int offset, int len) throws LdapException;
}
