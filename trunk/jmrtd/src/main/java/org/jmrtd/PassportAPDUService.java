/*
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

package org.jmrtd;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;

import net.sf.scuba.smartcards.APDUEvent;
import net.sf.scuba.smartcards.APDUWrapper;
import net.sf.scuba.smartcards.CardService;
import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.smartcards.CommandAPDU;
import net.sf.scuba.smartcards.ISO7816;
import net.sf.scuba.smartcards.ResponseAPDU;
import net.sf.scuba.tlv.TLVInputStream;
import net.sf.scuba.util.Hex;

/**
 * Low level card service for sending APDUs to the passport. This service is not
 * responsible for maintaining information about the state of the authentication
 * or secure messaging protocols. It merely offers the basic functionality for
 * sending passport specific APDUs to the passport.
 *
 * Based on ICAO-TR-PKI. Defines the following commands:
 * <ul>
 *     <li><code>GET CHALLENGE</code></li>
 *     <li><code>EXTERNAL AUTHENTICATE</code></li>
 *     <li><code>INTERNAL AUTHENTICATE</code> (using secure messaging)</li>
 *     <li><code>SELECT FILE</code> (using secure messaging)</li>
 *     <li><code>READ BINARY</code> (using secure messaging)</li>
 * </ul>
 *
 * @author The JMRTD team (info@jmrtd.org)
 *
 * @version $Revision$
 */
class PassportAPDUService extends CardService {

  /** Shared secret type for PACE according to BSI TR-03110 v2.03 B.11.1. */
  public static final byte MRZ_PACE_KEY_REFERENCE = 0x01;

  /** Shared secret type for PACE according to BSI TR-03110 v2.03 B.11.1. */
  public static final byte CAN_PACE_KEY_REFERENCE = 0x02;

  /** Shared secret type for PACE according to BSI TR-03110 v2.03 B.11.1. */
  public static final byte PIN_PACE_KEY_REFERENCE = 0x03;

  /** Shared secret type for PACE according to BSI TR-03110 v2.03 B.11.1. */
  public static final byte PUK_PACE_KEY_REFERENCE = 0x04;

  /** Invalid short identifier. */
  public static final int NO_SFI = -1;

  private static final Logger LOGGER = Logger.getLogger("org.jmrtd");

  private static final Provider BC_PROVIDER = Util.getBouncyCastleProvider();

  /** The applet we select when we start a session. */
  protected static final byte[] APPLET_AID = { (byte)0xA0, 0x00, 0x00, 0x02, 0x47, 0x10, 0x01 };

  /** Initialization vector used by the cipher below. */
  private static final IvParameterSpec ZERO_IV_PARAM_SPEC = new IvParameterSpec(new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 });

  /** The general Authenticate command is used to perform the PACE protocol. See Section 3.2.2 of SAC-TR 1.01. */
  private static final byte INS_PACE_GENERAL_AUTHENTICATE = (byte)0x86;

  /** The service we decorate. */
  private CardService service;

  private byte[] atr;

  /** DESede encryption/decryption cipher. */
  private Cipher cipher;

  /** ISO9797Alg3Mac. */
  private Mac mac;

  private int apduCount;

  /**
   * Creates a new passport APDU sending service.
   *
   * @param service another service which will deal with sending the APDUs to the card
   *
   * @throws CardServiceException when the available JCE providers cannot provide the necessary
   *                              cryptographic primitives:
   *                              <ul>
   *                                <li>Cipher: "DESede/CBC/Nopadding"</li>
   *             	                  <li>Mac: "ISO9797Alg3Mac"</li>
   *                              </ul>
   */
  public PassportAPDUService(CardService service) throws CardServiceException {
    if (service == null) {
      throw new IllegalArgumentException("Card service cannot be null");
    }

    this.service = service;
    this.apduCount = 0;
    try {
      this.mac = Mac.getInstance("ISO9797Alg3Mac", BC_PROVIDER);
      this.cipher = Util.getCipher("DESede/CBC/NoPadding");
    } catch (GeneralSecurityException gse) {
      throw new CardServiceException("Unexpected security exception during initialization", gse);
    }
  }

  /**
   * Opens a session by connecting to the card. Since version 0.5.1 this method no longer automatically
   * selects the MRTD applet, caller (for instance {@link PassportService}) is responsible to do this now.
   *
   * @throws CardServiceException on failure to open the service
   */
  @Override
  public void open() throws CardServiceException {
    if (!service.isOpen()) {
      service.open();
    }
    atr = service.getATR();
  }

  /**
   * Whether this service is open.
   *
   * @return a boolean
   */
  @Override
  public synchronized boolean isOpen() {
    return service.isOpen();
  }

  /**
   * Tranceives an APDU.
   *
   * If the card responds with a status word other than {@code 0x9000} this method does
   * NOT throw a {@linkplain CardServiceException}, but it returns this as error code
   * as result.
   * 
   * @param commandAPDU the command APDU
   *
   * @return the response APDU
   *
   * @throws CardServiceException on error
   */
  @Override
  public synchronized ResponseAPDU transmit(CommandAPDU commandAPDU) throws CardServiceException {
    ResponseAPDU responseAPDU = service.transmit(commandAPDU);
    notifyExchangedAPDU(new APDUEvent(this, "PLAIN", ++apduCount, commandAPDU, responseAPDU));
    return responseAPDU;
  }

  /**
   * Gets the answer to reset bytes.
   *
   * @return the answer to reset bytes
   */
  @Override
  public byte[] getATR() {
    return atr;
  }

  /**
   * Closes the service.
   */
  @Override
  public void close() {
    if (service != null) {
      service.close();
    }
  }

  /**
   * Transmits an APDU.
   *
   * @param wrapper the secure messaging wrapper
   * @param commandAPDU the APDU to send
   *
   * @return the APDU received from the PICC
   *
   * @throws CardServiceException if tranceiving failed
   */
  private ResponseAPDU transmit(APDUWrapper wrapper, CommandAPDU commandAPDU) throws CardServiceException {
    CommandAPDU plainCapdu = commandAPDU;
    if (wrapper != null) {
      commandAPDU = wrapper.wrap(commandAPDU);
    }
    ResponseAPDU responseAPDU = service.transmit(commandAPDU);
    ResponseAPDU rawRapdu = responseAPDU;
    short sw = (short)responseAPDU.getSW();
    if (wrapper == null) {
      notifyExchangedAPDU(new APDUEvent(this, "PLAIN", ++apduCount, commandAPDU, responseAPDU));
    } else {
      try {
        if (responseAPDU.getBytes().length <= 2) {
          throw new CardServiceException("Exception during transmission of wrapped APDU"
              + ", C=" + Hex.bytesToHexString(plainCapdu.getBytes()), sw);
        }

        responseAPDU = wrapper.unwrap(responseAPDU);
      } catch (CardServiceException cse) {
        throw cse;
      } catch (Exception e) {
        throw new CardServiceException("Exception during transmission of wrapped APDU"
            + ", C=" + Hex.bytesToHexString(plainCapdu.getBytes()), e, sw);
      } finally {
        notifyExchangedAPDU(new WrappedAPDUEvent(this, wrapper.getType(), ++apduCount, plainCapdu, responseAPDU, commandAPDU, rawRapdu));
      }
    }

    return responseAPDU;
  }

  /**
   * Sends a {@code SELECT APPLET} command to the card.
   *
   * @param wrapper the secure messaging wrapper to use
   * @param aid the applet to select
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized void sendSelectApplet(APDUWrapper wrapper, byte[] aid) throws CardServiceException {
    if (aid == null) {
      throw new IllegalArgumentException("AID cannot be null");
    }
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_SELECT_FILE, (byte) 0x04, (byte) 0x0C, aid);
    ResponseAPDU rapdu = transmit(wrapper, capdu);

    checkStatusWordAfterFileOperation(capdu, rapdu);
  }

  /**
   * Selects a file.
   *
   * @param fid the file identifier
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendSelectFile(short fid) throws CardServiceException {
    sendSelectFile(null, fid);
  }

  /**
   * Sends a {@code SELECT FILE} command to the passport. Secure
   * messaging will be applied to the command and response apdu.
   *
   * @param wrapper the secure messaging wrapper to use
   * @param fid the file to select
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized void sendSelectFile(APDUWrapper wrapper, short fid) throws CardServiceException {
    byte[] fiddle = { (byte) ((fid >> 8) & 0xFF), (byte) (fid & 0xFF) };
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_SELECT_FILE, (byte) 0x02, (byte) 0x0c, fiddle, 0);
    ResponseAPDU rapdu = transmit(wrapper, capdu);

    if (rapdu == null) {
      return;
    }

    checkStatusWordAfterFileOperation(capdu, rapdu);
  }

  /**
   * Sends a {@code READ BINARY} command to the passport.
   * Secure messaging will be applied to the command and response APDU.
   *
   * @param wrapper the secure messaging wrapper to use, or {@code null} for none
   * @param sfi the short file identifier byte of the file to read as an int value (between 0 and 255)
   *            only if {@code isSFIEnabled} is {@code true}, if not any value)
   * @param offset offset into the file
   *        (either a value between 0 and 255 if {@code isSFIEnabled} is {@code true},
   *        of a value between 0 and 65535 if not)
   * @param le the expected length of the file to read
   * @param isSFIEnabled a boolean indicating whether short file identifiers are used
   * @param isTLVEncodedOffsetNeeded a boolean indicating whether it should be a long ({@code INS == 0xB1}) read
   *
   * @return a byte array of length at most {@code le} with (the specified part of) the contents of the currently selected file
   *
   * @throws CardServiceException if the command was not successful
   */
  public synchronized byte[] sendReadBinary(APDUWrapper wrapper, int sfi, int offset, int le, boolean isSFIEnabled, boolean isTLVEncodedOffsetNeeded) throws CardServiceException {
    CommandAPDU commandAPDU = null;
    ResponseAPDU responseAPDU = null;

    // In case the data ended right on the block boundary
    if (le == 0) {
      return null;
    }

    byte offsetMSB = (byte)((offset & 0xFF00) >> 8);
    byte offsetLSB = (byte)(offset & 0xFF);

    if (isTLVEncodedOffsetNeeded) {
      // In the case of long read 2 or 3 bytes less of the actual data will be returned,
      // because a tag and length will be sent along, here we need to account for this.
      if (le < 128) {
        le += 2;
      } else if (le < 256) {
        le += 3;
      }
      if (le > 256) {
        le = 256;
      }

      byte[] data = new byte[] { 0x54, 0x02, offsetMSB, offsetLSB };
      commandAPDU = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_READ_BINARY2, 0, 0, data, le);
    } else if (isSFIEnabled) {
      commandAPDU = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_READ_BINARY, (byte)sfi, offsetLSB, le);      
    } else {
      commandAPDU = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_READ_BINARY, offsetMSB, offsetLSB, le);
    }

    short sw = ISO7816.SW_UNKNOWN;
    try {
      responseAPDU = transmit(wrapper, commandAPDU);
      sw = (short)responseAPDU.getSW();
    } catch (CardServiceException cse) {
      LOGGER.log(Level.FINE, "Exception during READ BINARY", cse);
      sw = (short)cse.getSW();
    }

    byte[] responseData = getResponseData(responseAPDU, isTLVEncodedOffsetNeeded);
    if (responseData == null || responseData.length == 0) {
      LOGGER.warning("DEBUG: rapduBytes = " + Arrays.toString(responseData) + ", le = " + le + ", sw = " + Integer.toHexString(sw));
    } else {
      checkStatusWordAfterFileOperation(commandAPDU, responseAPDU);
    }
    return responseData;
  }

  private byte[] getResponseData(ResponseAPDU responseAPDU, boolean isTLVEncodedOffsetNeeded) throws CardServiceException {
    if (responseAPDU == null) {
      return null;
    }

    byte[] responseData = responseAPDU.getData();
    if (responseData == null) {
      throw new CardServiceException("Malformed read binary long response data");      
    }
    if (!isTLVEncodedOffsetNeeded) {
      return responseData;
    }

    /* 
     * Strip the response off the tag 0x53 and the length field.
     * FIXME: Use TLVUtil.tlvEncode(...) here. -- MO
     */
    byte[] data = responseData;
    int index = 0;
    if (data[index++] != (byte)0x53) { // FIXME: Constant for 0x53.
      throw new CardServiceException("Malformed read binary long response data");
    }
    if ((byte)(data[index] & 0x80) == (byte)0x80) {
      index += (data[index] & 0xF);
    }
    index ++;
    responseData = new byte[data.length - index];
    System.arraycopy(data, index, responseData, 0, responseData.length);
    return responseData;
  }
    
  /**
   * Sends a {@code GET CHALLENGE} command to the passport.
   *
   * @return a byte array of length 8 containing the challenge
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized byte[] sendGetChallenge() throws CardServiceException {
    return sendGetChallenge(null);
  }

  /**
   * Sends a {@code GET CHALLENGE} command to the passport.
   *
   * @param wrapper secure messaging wrapper
   *
   * @return a byte array of length 8 containing the challenge
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized byte[] sendGetChallenge(APDUWrapper wrapper) throws CardServiceException {
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_GET_CHALLENGE, 0x00, 0x00, 8);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    return rapdu.getData();
  }

  /**
   * Sends an {@code INTERNAL AUTHENTICATE} command to the passport.
   * This is part of AA.
   *
   * @param wrapper secure messaging wrapper
   * @param rndIFD the challenge to send
   *
   * @return the response from the passport (status word removed)
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized byte[] sendInternalAuthenticate(APDUWrapper wrapper, byte[] rndIFD) throws CardServiceException {
    if (rndIFD == null || rndIFD.length != 8) {
      throw new IllegalArgumentException("rndIFD wrong length");
    }
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_INTERNAL_AUTHENTICATE, 0x00, 0x00, rndIFD, 256);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    return rapdu.getData();
  }

  /**
   * Sends an {@code EXTERNAL AUTHENTICATE} command to the passport.
   * This is part of BAC.
   * The resulting byte array has length 32 and contains {@code rndICC}
   * (first 8 bytes), {@code rndIFD} (next 8 bytes), their key material
   * {@code kICC} (last 16 bytes).
   *
   * @param rndIFD our challenge
   * @param rndICC their challenge
   * @param kIFD our key material
   * @param kEnc the static encryption key
   * @param kMac the static mac key
   *
   * @return a byte array of length 32 containing the response that was sent
   *         by the passport, decrypted (using {@code kEnc}) and verified
   *         (using {@code kMac})
   *
   * @throws CardServiceException on tranceive error
   */
  public synchronized byte[] sendMutualAuth(byte[] rndIFD, byte[] rndICC, byte[] kIFD, SecretKey kEnc, SecretKey kMac) throws CardServiceException {
    try {
      if (rndIFD == null || rndIFD.length != 8) {
        throw new IllegalArgumentException("rndIFD wrong length");
      }
      if (rndICC == null || rndICC.length != 8) {
        rndICC = new byte[8];
      }
      if (kIFD == null || kIFD.length != 16) {
        throw new IllegalArgumentException("kIFD wrong length");
      }
      if (kEnc == null) {
        throw new IllegalArgumentException("kEnc == null");
      }
      if (kMac == null) {
        throw new IllegalArgumentException("kMac == null");
      }

      cipher.init(Cipher.ENCRYPT_MODE, kEnc, ZERO_IV_PARAM_SPEC);
      byte[] plaintext = new byte[32];
      System.arraycopy(rndIFD, 0, plaintext, 0, 8);
      System.arraycopy(rndICC, 0, plaintext, 8, 8);
      System.arraycopy(kIFD, 0, plaintext, 16, 16);
      byte[] ciphertext = cipher.doFinal(plaintext);
      if (ciphertext.length != 32) {
        throw new IllegalStateException("Cryptogram wrong length " + ciphertext.length);
      }

      mac.init(kMac);
      byte[] mactext = mac.doFinal(Util.pad(ciphertext, 8));
      if (mactext.length != 8) {
        throw new IllegalStateException("MAC wrong length");
      }

      byte p1 = (byte)0x00;
      byte p2 = (byte)0x00;

      byte[] data = new byte[32 + 8];
      System.arraycopy(ciphertext, 0, data, 0, 32);
      System.arraycopy(mactext, 0, data, 32, 8);
      int le = 40; /* 40 means max ne is 40 (0x28). */
      CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_EXTERNAL_AUTHENTICATE, p1, p2, data, le);
      ResponseAPDU rapdu = transmit(capdu);

      if (rapdu == null) {
        throw new CardServiceException("Mutual authentication failed, received null response APDU");
      }

      byte[] rapduBytes = rapdu.getBytes();
      short sw = (short)rapdu.getSW();
      if (rapduBytes == null) {
        throw new CardServiceException("Mutual authentication failed, received empty data in response APDU", sw);
      }

      /* Some MRTDs apparently don't support 40 here, try again with 0. See R2-p1_v2_sIII_0035 (and other issues). */
      if (sw != ISO7816.SW_NO_ERROR) {
        le = 0; /* 0 means ne is max 256 (0xFF). */
        capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_EXTERNAL_AUTHENTICATE, p1, p2, data, le);
        rapdu = transmit(capdu);
        rapduBytes = rapdu.getBytes();
        sw = (short)rapdu.getSW();
      }

      if (rapduBytes.length != 42) {
        throw new CardServiceException("Mutual authentication failed: expected length: 40 + 2, actual length: " + rapduBytes.length, sw);
      }

      /* Decrypt the response. */
      cipher.init(Cipher.DECRYPT_MODE, kEnc, ZERO_IV_PARAM_SPEC);
      byte[] result = cipher.doFinal(rapduBytes, 0, rapduBytes.length - 8 - 2);
      if (result.length != 32) {
        throw new IllegalStateException("Cryptogram wrong length " + result.length);
      }
      return result;
    } catch (GeneralSecurityException gse) {
      throw new CardServiceException("Security exception during mutual auth", gse);
    }
  }

  /**
   * Sends the EXTERNAL AUTHENTICATE command.
   * This is used in EAC-TA.
   *
   * @param wrapper secure messaging wrapper
   * @param signature terminal signature
   *
   * @throws CardServiceException if the resulting status word different from 9000
   */
  public synchronized void sendMutualAuthenticate(APDUWrapper wrapper, byte[] signature) throws CardServiceException {
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_EXTERNAL_AUTHENTICATE, 0, 0, signature);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending External Authenticate failed.", sw);
    }
  }

  /**
   * The MSE KAT APDU, see EAC 1.11 spec, Section B.1
   *
   * @param wrapper secure messaging wrapper
   * @param keyData key data object (tag 0x91)
   * @param idData key id data object (tag 0x84), can be null
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendMSEKAT(APDUWrapper wrapper, byte[] keyData, byte[] idData) throws CardServiceException {
    byte[] data = new byte[keyData.length + ((idData != null) ? idData.length : 0)];
    System.arraycopy(keyData, 0, data, 0, keyData.length);
    if (idData != null) {
      System.arraycopy(idData, 0, data, keyData.length, idData.length);
    }

    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, 0x41, 0xA6, data);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending MSE KAT failed", sw);
    }
  }

  /**
   * The MSE DST APDU, see EAC 1.11 spec, Section B.2
   *
   * @param wrapper secure messaging wrapper
   * @param data public key reference data object (tag 0x83)
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendMSESetDST(APDUWrapper wrapper, byte[] data) throws CardServiceException {
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, 0x81, 0xB6, data);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending MSE Set DST failed", sw);
    }
  }

  /**
   * The MSE Set AT APDU for TA, see EAC 1.11 spec, Section B.2.
   * MANAGE SECURITY ENVIRONMENT command with SET Authentication Template function.
   *
   * Note that caller is responsible for prefixing the byte[] params with specified tags.
   *
   * @param wrapper secure messaging wrapper
   * @param data public key reference data object (should already be prefixed with tag 0x83)
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendMSESetATExtAuth(APDUWrapper wrapper, byte[] data) throws CardServiceException {
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, 0x81, 0xA4, data);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending MSE AT failed", sw);
    }
  }

  /*
   * FIXME: Make prefixing 0x8x tags responsibilities consistent between ext auth and int auth and mutual auth
   * Now: above method makes caller responsible, below method callee is responsible. -- MO
   */

  /* For Chip Authentication. We prefix 0x80 for OID and 0x84 for keyId. */
  /**
   * The  MSE Set AT for chip authentication.
   *
   * @param wrapper secure messaging wrapper
   * @param oid the OID
   * @param keyId the keyId or {@code null}
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendMSESetATIntAuth(APDUWrapper wrapper, String oid, BigInteger keyId) throws CardServiceException {
    int p1 = 0x41;
    int p2 = 0xA4;
    //  int p2 = 0xA6;
    ResponseAPDU rapdu = null;
    if (keyId == null || keyId.compareTo(BigInteger.ZERO) < 0) {
      CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, p1, p2, toOIDBytes(oid));
      rapdu = transmit(wrapper, capdu);
    } else {
      byte[] oidBytes = toOIDBytes(oid);
      byte[] keyIdBytes = Util.wrapDO((byte)0x84, Util.i2os(keyId));
      ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
      try {
        byteArrayOutputStream.write(oidBytes);
        byteArrayOutputStream.write(keyIdBytes);
        byteArrayOutputStream.close();
      } catch (IOException ioe) {
        LOGGER.log(Level.WARNING, "Exception", ioe);
      }
      CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, p1, p2, byteArrayOutputStream.toByteArray());
      rapdu = transmit(wrapper, capdu);
    }
    short sw = rapdu == null ? -1 : (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending MSE AT failed", sw);
    }
  }

  /**
   * The MSE AT APDU for PACE, see ICAO TR-SAC-1.01, Section 3.2.1, BSI TR 03110 v2.03 B11.1.
   * Note that (for now) caller is responsible for prefixing the byte[] params with specified tags.
   *
   * @param wrapper secure messaging wrapper
   * @param oid OID of the protocol to select (this method will prefix {@code 0x80})
   * @param refPublicKeyOrSecretKey value specifying whether to use MRZ ({@code 0x01}) or CAN ({@code 0x02}) (this method will prefix {@code 0x83})
   * @param refPrivateKeyOrForComputingSessionKey indicates a private key or reference for computing a session key (this method will prefix {@code 0x84})
   *
   * @throws CardServiceException on error
   */
  public synchronized void sendMSESetATMutualAuth(APDUWrapper wrapper, String oid,
      int refPublicKeyOrSecretKey, byte[] refPrivateKeyOrForComputingSessionKey) throws CardServiceException {

    if (oid == null) {
      throw new IllegalArgumentException("OID cannot be null");
    }

    byte[] oidBytes = toOIDBytes(oid);

    /*
     * 0x83 Reference of a public key / secret key.
     * The password to be used is indicated as follows: 0x01: MRZ, 0x02: CAN.
     */
    if (!(refPublicKeyOrSecretKey == MRZ_PACE_KEY_REFERENCE
        || refPublicKeyOrSecretKey == CAN_PACE_KEY_REFERENCE
        || refPublicKeyOrSecretKey == PIN_PACE_KEY_REFERENCE
        || refPublicKeyOrSecretKey == PUK_PACE_KEY_REFERENCE)) {
      throw new IllegalArgumentException("Unsupported key type reference (MRZ, CAN, etc), found " + refPublicKeyOrSecretKey);
    }

    byte[] refPublicKeyOrSecretKeyBytes = Util.wrapDO((byte)0x83, new byte[] { (byte)refPublicKeyOrSecretKey }); /* FIXME: define constant for 0x83 */

    /*
     * 0x84 Reference of a private key / Reference for computing a
     * session key.
     * This data object is REQUIRED to indicate the identifier
     * of the domain parameters to be used if the domain
     * parameters are ambiguous, i.e. more than one set of
     * domain parameters is available for PACE.
     */
    if (refPrivateKeyOrForComputingSessionKey != null) {
      refPrivateKeyOrForComputingSessionKey = Util.wrapDO((byte)0x84, refPrivateKeyOrForComputingSessionKey);
    }

    /* Construct data. */
    ByteArrayOutputStream dataOutputStream = new ByteArrayOutputStream();
    try {
      dataOutputStream.write(oidBytes);
      dataOutputStream.write(refPublicKeyOrSecretKeyBytes);
      if (refPrivateKeyOrForComputingSessionKey != null) {
        dataOutputStream.write(refPrivateKeyOrForComputingSessionKey);
      }
    } catch (IOException ioe) {
      /* NOTE: should never happen. */
      LOGGER.log(Level.WARNING, "Error while copying data", ioe);
      throw new IllegalStateException("Error while copying data");
    }
    byte[] data = dataOutputStream.toByteArray();

    /* Tranceive APDU. */
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_MSE, 0xC1, 0xA4, data);
    ResponseAPDU rapdu = transmit(wrapper, capdu);

    /* Handle error status word. */
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending MSE AT failed", sw);
    }
  }

  /**
   * Sends a General Authenticate command.
   *
   * @param wrapper secure messaging wrapper
   * @param data data to be sent, without the {@code 0x7C} prefix (this method will add it)
   * @param isLast indicates whether this is the last command in the chain
   *
   * @return dynamic authentication data without the {@code 0x7C} prefix (this method will remove it)
   *
   * @throws CardServiceException on error
   */
  public synchronized byte[] sendGeneralAuthenticate(APDUWrapper wrapper, byte[] data, boolean isLast) throws CardServiceException {
    /* Tranceive APDU. */
    byte[] commandData = Util.wrapDO((byte)0x7C, data); // FIXME: constant for 0x7C
    CommandAPDU capdu = new CommandAPDU(isLast ? ISO7816.CLA_ISO7816 : ISO7816.CLA_COMMAND_CHAINING, INS_PACE_GENERAL_AUTHENTICATE, 0x00, 0x00, commandData, 256);
    ResponseAPDU rapdu = transmit(wrapper, capdu);

    /* Handle error status word. */
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending general authenticate failed", sw);
    }
    byte[] responseData = rapdu.getData();
    responseData = Util.unwrapDO((byte)0x7C, responseData);
    return responseData;
  }

  /**
   * Sends a perform security operation command in extended length mode.
   *
   * @param wrapper secure messaging wrapper
   * @param certBodyData the certificate body
   * @param certSignatureData signature data
   *
   * @throws CardServiceException on error communicating over the service
   */
  public synchronized void sendPSOExtendedLengthMode(APDUWrapper wrapper, byte[] certBodyData, byte[] certSignatureData)
      throws CardServiceException {
    byte[] certData = new byte[certBodyData.length + certSignatureData.length];
    System.arraycopy(certBodyData, 0, certData, 0, certBodyData.length);
    System.arraycopy(certSignatureData, 0, certData, certBodyData.length, certSignatureData.length);

    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816, ISO7816.INS_PSO, 0, 0xBE, certData);
    ResponseAPDU rapdu = transmit(wrapper, capdu);
    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending PSO failed", sw);
    }
  }

  /**
   * Sends a perform security operation command in chain mode.
   *
   * @param wrapper secure messaging wrapper
   * @param certBodyData the certificate body
   * @param certSignatureData signature data
   *
   * @throws CardServiceException on error communicating over the service
   */
  public synchronized void sendPSOChainMode(APDUWrapper wrapper, byte[] certBodyData, byte[] certSignatureData) throws CardServiceException {
    byte[] certData = new byte[certBodyData.length + certSignatureData.length];
    System.arraycopy(certBodyData, 0, certData, 0, certBodyData.length);
    System.arraycopy(certSignatureData, 0, certData, certBodyData.length, certSignatureData.length);
    int maxBlock = 223;
    int blockSize = 223;
    int offset = 0;
    int length = certData.length;
    if (certData.length > maxBlock) {
      int numBlock = certData.length / blockSize;
      if (numBlock * blockSize < certData.length) {
        numBlock++;
      }
      int i = 0;
      while (i < numBlock - 1) {
        CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816 | 0x10, ISO7816.INS_PSO, 0x00, 0xBE, certData, offset, length);
        ResponseAPDU rapdu = transmit(wrapper, capdu);
        short sw = (short)rapdu.getSW();
        if (sw != ISO7816.SW_NO_ERROR) {
          throw new CardServiceException("Sending PSO failed", sw);
        }
        length -= blockSize;
        offset += blockSize;
        i++;
      }
    }
    CommandAPDU capdu = new CommandAPDU(ISO7816.CLA_ISO7816 | 0x00, ISO7816.INS_PSO, 0x00, 0xBE, certData, offset, length);
    ResponseAPDU rapdu = transmit(wrapper, capdu);

    short sw = (short)rapdu.getSW();
    if (sw != ISO7816.SW_NO_ERROR) {
      throw new CardServiceException("Sending PSO failed", sw);
    }
  }

  /*
   * 0x80 Cryptographic mechanism reference
   * Object Identifier of the protocol to select (value only, tag 0x06 is omitted).
   */
  private byte[] toOIDBytes(String oid) {
    byte[] oidBytes = null;
    try {
      TLVInputStream oidTLVIn = new TLVInputStream(new ByteArrayInputStream(new ASN1ObjectIdentifier(oid).getEncoded()));
      try {
        oidTLVIn.readTag(); /* Should be 0x06 */
        oidTLVIn.readLength();
        oidBytes = oidTLVIn.readValue();
      } finally {
        oidTLVIn.close();
      }
      return Util.wrapDO((byte)0x80, oidBytes); /* FIXME: define constant for 0x80. */
    } catch (IOException ioe) {
      throw new IllegalArgumentException("Illegal OID: \"" + oid, ioe);
    }
  }

  private static void checkStatusWordAfterFileOperation(CommandAPDU capdu, ResponseAPDU rapdu) throws CardServiceException {
    short sw = (short)rapdu.getSW();
    String commandResponseMessage = "CAPDU = " + Hex.bytesToHexString(capdu.getBytes()) + ", RAPDU = " + Hex.bytesToHexString(rapdu.getBytes());
    switch(sw) {
      case ISO7816.SW_NO_ERROR:
        return;
      case ISO7816.SW_FILE_NOT_FOUND:
        throw new CardServiceException("File not found, " + commandResponseMessage, sw);
      case ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED: /* NOTE: fall through. */
      case ISO7816.SW_CONDITIONS_NOT_SATISFIED: /* NOTE: fall through. */
      case ISO7816.SW_COMMAND_NOT_ALLOWED:
        throw new CardServiceException("Access to file denied, " + commandResponseMessage, sw);
      default:
        throw new CardServiceException("Error occured, " + commandResponseMessage, sw);
    }
  }
}
