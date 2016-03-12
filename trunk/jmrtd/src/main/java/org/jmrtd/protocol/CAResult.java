/*
 * JMRTD - A Java API for accessing machine readable travel documents.
 *
 * Copyright (C) 2006 - 2016  The JMRTD team
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * $Id: $
 */

package org.jmrtd.protocol;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PublicKey;

import org.jmrtd.SecureMessagingWrapper;

/**
 * Result of EAC Chip Authentication protocol.
 *
 * @author The JMRTD team (info@jmrtd.org)
 *
 * @version $Revision: $
 */
public class CAResult {
  
  private BigInteger keyId;
  private PublicKey publicKey;
  private SecureMessagingWrapper wrapper;
  private byte[] keyHash;
  private KeyPair keyPair;
  
  /**
   * Creates a result.
   *
   * @param keyId the key identifier of the ICC's public key or -1
   * @param publicKey the ICC's public key
   * @param keyHash the hash of the key
   * @param keyPair the key pair
   */
  public CAResult(BigInteger keyId, PublicKey publicKey, SecureMessagingWrapper wrapper, byte[] keyHash, KeyPair keyPair) {
    this.keyId = keyId;
    this.publicKey = publicKey;
    this.keyHash = keyHash;
    this.keyPair = keyPair;
    this.wrapper = wrapper;
  }
  
  /**
   * Gets the ICC's public key identifier
   *
   * @return the key id or -1
   */
  public BigInteger getKeyId() {
    return keyId;
  }
  
  /**
   * Gets the ICC's public key that was used as input to chip authentication protocol
   *
   * @return the public key
   */
  public PublicKey getPublicKey() {
    return publicKey;
  }
  
  public SecureMessagingWrapper getWrapper() {
    return wrapper;
  }
  
  /**
   * Gets the hash of the key.
   *
   * @return the hash of the key
   */
  public byte[] getKeyHash() {
    return keyHash;
  }
  
  /**
   * The ephemeral key pair resulting from chip authentication.
   *
   * @return a key pair
   */
  public KeyPair getKeyPair() {
    return keyPair;
  }
}