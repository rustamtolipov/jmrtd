/*
 * JMRTD - A Java API for accessing machine readable travel documents.
 *
 * Copyright (C) 2006 - 2017  The JMRTD team
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

package org.jmrtd.test.protocol;

import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.jmrtd.protocol.DESedeSecureMessagingWrapper;
import org.jmrtd.protocol.SecureMessagingWrapper;

import junit.framework.TestCase;
import net.sf.scuba.smartcards.CommandAPDU;

public class DESedeSecureMessagingWrapperTest extends TestCase {

  private static final Logger LOGGER = Logger.getLogger("org.jmrtd");

  public void testDESedeSecureMessagingWrapper() {
    try {
      SecretKey encKey = getRandomDESedeKey();
      SecretKey macKey = getRandomDESedeKey();
      SecureMessagingWrapper wrapper = new DESedeSecureMessagingWrapper(encKey, macKey);

      assertEquals(encKey, wrapper.getEncryptionKey());
      assertEquals(macKey, wrapper.getMACKey());
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }
  
  public void testDESedeSecureMessagingWrapperWrapUnwrap() {
    try {
      SecretKey encKey = getRandomDESedeKey();
      SecretKey macKey = getRandomDESedeKey();
      SecureMessagingWrapper wrapper = new DESedeSecureMessagingWrapper(encKey, macKey);

      CommandAPDU commandAPDU = new CommandAPDU(0x00, 0xA4, 0x00, 0x00, new byte[] { 0x3F, 0x00 }, 0x00);
      CommandAPDU wrappedCommandAPDU = wrapper.wrap(commandAPDU);
      
      assertNotNull(wrappedCommandAPDU);
      assertEquals(0x0C, wrappedCommandAPDU.getCLA());
      assertEquals(commandAPDU.getINS(), wrappedCommandAPDU.getINS());
      assertEquals(commandAPDU.getP1(), wrappedCommandAPDU.getP1());
      assertEquals(commandAPDU.getP2(), wrappedCommandAPDU.getP2());
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Unexpected exception", e);
      fail(e.getMessage());
    }
  }

  private static SecretKey getRandomDESedeKey() throws NoSuchAlgorithmException {
    KeyGenerator keyFactory = KeyGenerator.getInstance("DESede");
    return keyFactory.generateKey();
  }
}