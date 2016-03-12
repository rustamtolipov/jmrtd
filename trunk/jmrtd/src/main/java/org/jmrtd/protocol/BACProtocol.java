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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.SecretKey;

import org.jmrtd.BACKeySpec;
import org.jmrtd.DESedeSecureMessagingWrapper;
import org.jmrtd.PassportApduService;
import org.jmrtd.Util;

import net.sf.scuba.smartcards.CardServiceException;

/**
 * The Basic Access Control protocol.
 * 
 * @author The JMRTD team (info@jmrtd.org)
 * 
 * @version $Revision: $
 * 
 * @since 0.5.6
 */
public class BACProtocol {
  
  private static final Logger LOGGER = Logger.getLogger("org.jmrtd");
  
  private PassportApduService service;
  private Random random;

  /**
   * Constructs a BAC protocol instance.
   * 
   * @param service the service to send APDUs
   */
  public BACProtocol(PassportApduService service) {
    this.service = service;
    this.random = new SecureRandom();
  }
  
  /**
   * Performs the <i>Basic Access Control</i> protocol.
   *
   * @param bacKey the key based on the document number,
   *               the card holder's birth date,
   *               and the document's expiry date
   *
   * @throws CardServiceException if authentication failed
   */
  public BACResult doBAC(BACKeySpec bacKey) throws CardServiceException {
    try {
      byte[] keySeed = computeKeySeedForBAC(bacKey);
      SecretKey kEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
      SecretKey kMac = Util.deriveKey(keySeed, Util.MAC_MODE);
      
      return doBAC(kEnc, kMac);
    } catch (CardServiceException cse) {
      LOGGER.log(Level.WARNING, "BAC failed", cse);
      throw cse;
    } catch (GeneralSecurityException gse) {
      LOGGER.log(Level.WARNING, "Error during BAC", gse);
      throw new CardServiceException(gse.toString());
    }
  }
  
  /**
   * Performs the <i>Basic Access Control</i> protocol.
   * It does BAC using kEnc and kMac keys, usually calculated
   * from the document number, the card holder's date of birth,
   * and the card's date of expiry.
   *
   * @param kEnc 3DES key required for BAC
   * @param kMac 3DES key required for BAC
   *
   * @return the new secure messaging wrapper
   *
   * @throws CardServiceException if authentication failed
   * @throws GeneralSecurityException on security primitives related problems
   */
  public BACResult doBAC(SecretKey kEnc, SecretKey kMac) throws CardServiceException, GeneralSecurityException {
    byte[] rndICC = service.sendGetChallenge();
    byte[] rndIFD = new byte[8];
    random.nextBytes(rndIFD);
    byte[] kIFD = new byte[16];
    random.nextBytes(kIFD);
    byte[] response = service.sendMutualAuth(rndIFD, rndICC, kIFD, kEnc, kMac);
    byte[] kICC = new byte[16];
    System.arraycopy(response, 16, kICC, 0, 16);
    byte[] keySeed = new byte[16];
    for (int i = 0; i < 16; i++) {
      keySeed[i] = (byte) ((kIFD[i] & 0xFF) ^ (kICC[i] & 0xFF));
    }
    SecretKey ksEnc = Util.deriveKey(keySeed, Util.ENC_MODE);
    SecretKey ksMac = Util.deriveKey(keySeed, Util.MAC_MODE);
    long ssc = Util.computeSendSequenceCounter(rndICC, rndIFD);
    return new BACResult(new DESedeSecureMessagingWrapper(ksEnc, ksMac, ssc));
  }
  
  private static byte[] computeKeySeedForBAC(BACKeySpec bacKey) throws GeneralSecurityException {
    String documentNumber = bacKey.getDocumentNumber();
    String dateOfBirth = bacKey.getDateOfBirth();
    String dateOfExpiry = bacKey.getDateOfExpiry();
    
    if (dateOfBirth == null || dateOfBirth.length() != 6) {
      throw new IllegalArgumentException("Wrong date format used for date of birth. Expected yyMMdd, found " + dateOfBirth);
    }
    if (dateOfExpiry == null || dateOfExpiry.length() != 6) {
      throw new IllegalArgumentException("Wrong date format used for date of expiry. Expected yyMMdd, found " + dateOfExpiry);
    }
    if (documentNumber == null) {
      throw new IllegalArgumentException("Wrong document number. Found " + documentNumber);
    }
    
    documentNumber = fixDocumentNumber(documentNumber);
    
    byte[] keySeed = Util.computeKeySeedForBAC(documentNumber, dateOfBirth, dateOfExpiry);
    
    return keySeed;
  }
  
  private static String fixDocumentNumber(String documentNumber) {
    /* The document number, excluding trailing '<'. */
    String minDocumentNumber = documentNumber.replace('<', ' ').trim().replace(' ', '<');
    
    /* The document number, including trailing '<' until length 9. */
    String maxDocumentNumber = minDocumentNumber;
    while (maxDocumentNumber.length() < 9) {
      maxDocumentNumber += "<";
    }
    return maxDocumentNumber;
  }
}