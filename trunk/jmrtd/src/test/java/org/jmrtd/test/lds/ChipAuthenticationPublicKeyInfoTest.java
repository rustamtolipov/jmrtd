/*
 *  JMRTD Tests.
 *
 *  Copyright (C) 2010 - 2017  The JMRTD team
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  $Id: $
 */

package org.jmrtd.test.lds;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Provider;
import java.security.PublicKey;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo;

import junit.framework.TestCase;

public class ChipAuthenticationPublicKeyInfoTest extends TestCase {

  private static final Provider BC_PROVIDER = new BouncyCastleProvider();

  public void testConstruct() {
    try {
      ChipAuthenticationPublicKeyInfo chipAuthenticationPublicKeyInfo = getSampleObject();
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  public void testSerializable() {
    try {
      ChipAuthenticationPublicKeyInfo chipAuthenticationPublicKeyInfo = getSampleObject();
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
      objectOutputStream.writeObject(chipAuthenticationPublicKeyInfo);
    } catch (Exception e) {
      fail(e.getMessage());
    }

  }

  /** Elaborate sample. */
  public ChipAuthenticationPublicKeyInfo getSampleObject() {
    try {
      /* Using BC here, since SunJCE doesn't support EC. */
      KeyPairGenerator keyGen1 = KeyPairGenerator.getInstance("EC", BC_PROVIDER);
      keyGen1.initialize(192);
      KeyPair keyPair1 = keyGen1.generateKeyPair();

      PublicKey publicKey1 = keyPair1.getPublic();

      return new ChipAuthenticationPublicKeyInfo(publicKey1, BigInteger.valueOf(1));
    } catch(Exception e) {
      fail(e.getMessage());
      return null;
    }
  }
}
