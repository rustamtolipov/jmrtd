/*
 * JMRTD - A Java API for accessing machine readable travel documents.
 *
 * Copyright (C) 2006 - 2018  The JMRTD team
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
 * $Id$
 */

package org.jmrtd.test.lds;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jmrtd.Util;
import org.jmrtd.lds.icao.DG15File;

import junit.framework.TestCase;
import net.sf.scuba.util.Hex;

public class DG15FileTest extends TestCase {

  private static final Logger LOGGER = Logger.getLogger("org.jmrtd");

  private static final Provider BC_PROVIDER = Util.getBouncyCastleProvider();

  public DG15FileTest(String name) {
    super(name);
  }

  public void testReflexive() {
    testReflexive(createTestObject());
  }

  public void testReflexive(DG15File dg15File) {
    try {
      byte[] encoded = dg15File.getEncoded();

      ByteArrayInputStream in = new ByteArrayInputStream(encoded);
      DG15File copy = new DG15File(in);

      assertEquals(dg15File, copy);
      assertEquals(dg15File.getPublicKey(), copy.getPublicKey());

      byte[] encodedCopy = copy.getEncoded();
      assertEquals(Hex.bytesToHexString(encoded), Hex.bytesToHexString(encodedCopy));

    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Exception", e);
      fail(e.getMessage());
    }
  }

  public void testGetPublic() {
    try {
      KeyPair keyPair = createTestKeyPair();
      PublicKey publicKey = keyPair.getPublic();
      DG15File dg15File = new DG15File(publicKey);
      PublicKey otherPublicKey = dg15File.getPublicKey();
      assertEquals(publicKey, otherPublicKey);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Exception", e);
      fail(e.getMessage());
    }
  }

  public void testNewNonX509() {
    try {
      //			Security.insertProviderAt(BC_PROVIDER, 4);

      /* This is a live DG15 public key as found in a Dutch 2014 PP. */
      byte[] dgValue = {
          0x30, (byte)0x82, 0x01, 0x75, 0x30, (byte)0x82, 0x01, 0x1D, 0x06, 0x07, 0x2A, (byte)0x86, 0x48, (byte)0xCE, 0x3D, 0x02,
          0x01, 0x30, (byte)0x82, 0x01, 0x10, 0x02, 0x01, 0x01, 0x30, 0x34, 0x06, 0x07, 0x2A, (byte)0x86, 0x48, (byte)0xCE,
          0x3D, 0x01, 0x01, 0x02, 0x29, 0x00, (byte)0xD3, 0x5E, 0x47, 0x20, 0x36, (byte)0xBC, 0x4F, (byte)0xB7, (byte)0xE1, 0x3C,
          0x78, 0x5E, (byte)0xD2, 0x01, (byte)0xE0, 0x65, (byte)0xF9, (byte)0x8F, (byte)0xCF, (byte)0xA6, (byte)0xF6, (byte)0xF4, 0x0D, (byte)0xEF, 0x4F, (byte)0x92,
          (byte)0xB9, (byte)0xEC, 0x78, (byte)0x93, (byte)0xEC, 0x28, (byte)0xFC, (byte)0xD4, 0x12, (byte)0xB1, (byte)0xF1, (byte)0xB3, 0x2E, 0x27, 0x30, 0x54,
          0x04, 0x28, 0x3E, (byte)0xE3, 0x0B, 0x56, (byte)0x8F, (byte)0xBA, (byte)0xB0, (byte)0xF8, (byte)0x83, (byte)0xCC, (byte)0xEB, (byte)0xD4, 0x6D, 0x3F,
          0x3B, (byte)0xB8, (byte)0xA2, (byte)0xA7, 0x35, 0x13, (byte)0xF5, (byte)0xEB, 0x79, (byte)0xDA, 0x66, 0x19, 0x0E, (byte)0xB0, (byte)0x85, (byte)0xFF,
          (byte)0xA9, (byte)0xF4, (byte)0x92, (byte)0xF3, 0x75, (byte)0xA9, 0x7D, (byte)0x86, 0x0E, (byte)0xB4, 0x04, 0x28, 0x52, 0x08, (byte)0x83, (byte)0x94,
          (byte)0x9D, (byte)0xFD, (byte)0xBC, 0x42, (byte)0xD3, (byte)0xAD, 0x19,(byte) 0x86, 0x40, 0x68, (byte)0x8A, 0x6F, (byte)0xE1, 0x3F, 0x41, 0x34,
          (byte)0x95, 0x54, (byte)0xB4, (byte)0x9A, (byte)0xCC, 0x31,(byte) 0xDC, (byte)0xCD, (byte)0x88, 0x45, 0x39, (byte)0x81, 0x6F, 0x5E, (byte)0xB4, (byte)0xAC,
          (byte)0x8F, (byte)0xB1, (byte)0xF1, (byte)0xA6, 0x04, 0x51, 0x04, 0x43, (byte)0xBD, 0x7E, (byte)0x9A, (byte)0xFB, 0x53, (byte)0xD8, (byte)0xB8, 0x52,
          (byte)0x89, (byte)0xBC, (byte)0xC4, (byte)0x8E, (byte)0xE5, (byte)0xBF, (byte)0xE6, (byte)0xF2, 0x01, 0x37, (byte)0xD1, 0x0A, 0x08, 0x7E, (byte)0xB6, (byte)0xE7,
          (byte)0x87, 0x1E, 0x2A, 0x10, (byte)0xA5, (byte)0x99, (byte)0xC7, 0x10, (byte)0xAF, (byte)0x8D, 0x0D, 0x39, (byte)0xE2, 0x06, 0x11, 0x14,
          (byte)0xFD, (byte)0xD0, 0x55, 0x45, (byte)0xEC, 0x1C, (byte)0xC8, (byte)0xAB, 0x40, (byte)0x93, 0x24, 0x7F, 0x77, 0x27, 0x5E, 0x07,
          0x43,(byte) 0xFF, (byte)0xED, 0x11, 0x71, (byte)0x82, (byte)0xEA, (byte)0xA9, (byte)0xC7, 0x78, 0x77, (byte)0xAA, (byte)0xAC, 0x6A, (byte)0xC7, (byte)0xD3,
          0x52, 0x45, (byte)0xD1, 0x69, 0x2E, (byte)0x8E,(byte) 0xE1, 0x02, 0x29, 0x00, (byte)0xD3, 0x5E, 0x47, 0x20, 0x36, (byte)0xBC,
          0x4F, (byte)0xB7, (byte)0xE1, 0x3C, 0x78, 0x5E, (byte)0xD2, 0x01, (byte)0xE0, 0x65, (byte)0xF9, (byte)0x8F,(byte) 0xCF, (byte)0xA5, (byte)0xB6, (byte)0x8F,
          0x12,(byte) 0xA3, 0x2D, 0x48, 0x2E, (byte)0xC7, (byte)0xEE, (byte)0x86, 0x58, (byte)0xE9, (byte)0x86, (byte)0x91, 0x55, 0x5B, 0x44, (byte)0xC5,
          (byte)0x93, 0x11, 0x02, 0x01, 0x01, 0x03, 0x52, 0x00, 0x04, 0x56, (byte)0xD1, 0x2E, 0x16, 0x6B, 0x35, 0x53,
          0x65, (byte)0xB6, (byte)0xBC, (byte)0xC6, 0x2C, (byte)0xBB, 0x7C, 0x3C, 0x58, 0x12, (byte)0xB7, 0x3F, (byte)0x92, (byte)0xD1, 0x37, 0x5C,
          (byte)0xB8, (byte)0x87, (byte)0xE4, 0x59, 0x00, 0x5A, (byte)0xFB, (byte)0xAE, 0x60, (byte)0xDE, 0x5A, (byte)0xB3, 0x12, (byte)0x8D, 0x51, 0x1B,
          (byte)0xEC, 0x04, (byte)0xA0, (byte)0xF3, (byte)0xFC, (byte)0xB6, 0x47, (byte)0xEF, (byte)0xF3, 0x4C, (byte)0x97, (byte)0xCD, (byte)0xF3, (byte)0xF6, 0x1A, 0x4F,
          0x6B, (byte)0xFB, (byte)0xC9, (byte)0x80, 0x46, (byte)0xC5, (byte)0xB5, (byte)0xD7, (byte)0xB5, 0x76, 0x2B, 0x72, 0x5E, 0x73, (byte)0xA3, (byte)0xC5,
          (byte)0x96, 0x2D, 0x0C, (byte)0xBA, 0x29, (byte)0xC7, (byte)0x84, 0x53, (byte)0x81
      };
      X509EncodedKeySpec spec = new X509EncodedKeySpec(dgValue);
      KeyFactory kf = KeyFactory.getInstance("EC", BC_PROVIDER);
      PublicKey publicKey = kf.generatePublic(spec);
      ECPublicKey ecPublicKey = (ECPublicKey)publicKey;
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Exception", e);
      fail(e.getMessage());
    }
  }

  public static DG15File createTestObject() {
    try {
      KeyPair keyPair = createTestKeyPair();
      return new DG15File(keyPair.getPublic());
    } catch (NoSuchAlgorithmException nsae) {
      return null;
    }
  }

  private static KeyPair createTestKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
    keyPairGenerator.initialize(1024);
    return keyPairGenerator.generateKeyPair();
  }

  public void testFile(InputStream in) {
    try {
      testReflexive(new DG15File(in));
    } catch (IOException ioe) {
      LOGGER.log(Level.WARNING, "Exception", ioe);
      fail(ioe.getMessage());
    }
  }
}
