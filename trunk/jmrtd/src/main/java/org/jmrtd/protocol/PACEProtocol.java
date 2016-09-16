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
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.IvParameterSpec;

import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.jmrtd.AESSecureMessagingWrapper;
import org.jmrtd.BACKeySpec;
import org.jmrtd.DESedeSecureMessagingWrapper;
import org.jmrtd.JMRTDSecurityProvider;
import org.jmrtd.PACEException;
import org.jmrtd.PassportApduService;
import org.jmrtd.PassportService;
import org.jmrtd.SecureMessagingWrapper;
import org.jmrtd.Util;
import org.jmrtd.lds.PACEInfo;
import org.jmrtd.lds.PACEInfo.MappingType;

import net.sf.scuba.smartcards.CardServiceException;
import net.sf.scuba.util.Hex;

/**
 * The Password Authenticated Connection Establishment protocol.
 * 
 * @author The JMRTD team (info@jmrtd.org)
 * 
 * @version $Revision: $
 * 
 * @since 0.5.6
 */
public class PACEProtocol {
  
  private static final Logger LOGGER = Logger.getLogger("org.jmrtd");
  
  private static final Provider BC_PROVIDER = JMRTDSecurityProvider.getBouncyCastleProvider();
  
  /**
   * Used in the last step of PACE-CAM.
   * 
   * From 9303-11:
   * 
   * AES [19] SHALL be used in CBC-mode according to [ISO/IEC 10116]
   * with IV=E(KSEnc,-1), where -1 is the bit string of length 128
   * with all bits set to 1.
   */
  private static final byte[] IV_FOR_PACE_CAM_DECRYPTION = {
      (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
      (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
      (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
      (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
  };
  
  private PassportService service;
  private SecureMessagingWrapper wrapper;
  
  private Random random;
  
  /**
   * Constructs a PACE protocol instance.
   * 
   * @param service the service for sending APDUs
   * @param wrapper the already established secure messaging channel (or {@code null})
   */
  public PACEProtocol(PassportService service, SecureMessagingWrapper wrapper) {
    this.service = service;
    this.wrapper = wrapper;
    this.random = new SecureRandom();
  }
  
  /**
   * Performs the PACE 2.0 / SAC protocol.
   *
   * @param keySpec the MRZ
   * @param oid as specified in the PACEInfo, indicates GM or IM or CAM, DH or ECDH, cipher, digest, length
   * @param params explicit static domain parameters the domain params for DH or ECDH
   * 
   * @return a PACE result
   *
   * @throws PACEException on error
   */
  public PACEResult doPACE(KeySpec keySpec, String oid, AlgorithmParameterSpec params) throws PACEException {
    try {
      return doPACE(deriveStaticPACEKey(keySpec, oid), oid, params);
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side error in key derivation step");
    }
  }
  /**
   * Performs the PACE 2.0 / SAC protocol.
   *
   * @param staticPACEKey the password key
   * @param oid as specified in the PACEInfo, indicates GM or IM or CAM, DH or ECDH, cipher, digest, length
   * @param params explicit static domain parameters the domain params for DH or ECDH
   * 
   * @return a PACE result
   *
   * @throws PACEException on error
   */
  public PACEResult doPACE(SecretKey staticPACEKey, String oid, AlgorithmParameterSpec params) throws PACEException {
    MappingType mappingType = PACEInfo.toMappingType(oid); /* Either GM, CAM, or IM. */
    String agreementAlg = PACEInfo.toKeyAgreementAlgorithm(oid); /* Either DH or ECDH. */
    String cipherAlg  = PACEInfo.toCipherAlgorithm(oid); /* Either DESede or AES. */
    String digestAlg = PACEInfo.toDigestAlgorithm(oid); /* Either SHA-1 or SHA-256. */
    int keyLength = PACEInfo.toKeyLength(oid); /* Of the enc cipher. Either 128, 192, or 256. */
    
    LOGGER.info("DEBUG: PACE: oid = " + oid
        + " -> mappingType = " + mappingType
        + ", agreementAlg = " + agreementAlg
        + ", cipherAlg = " + cipherAlg
        + ", digestAlg = " + digestAlg
        + ", keyLength = " + keyLength);
    
    /* Check consistency of input parameters. */
    if (agreementAlg == null) {
      throw new IllegalArgumentException("Unknown agreement algorithm");
    }
    if (!("ECDH".equals(agreementAlg) || "DH".equals(agreementAlg))) {
      throw new IllegalArgumentException("Unsupported agreement algorithm, expected ECDH or DH, found " + agreementAlg);  
    }
    if ("ECDH".equals(agreementAlg) && !(params instanceof ECParameterSpec)) {
      throw new IllegalArgumentException("Expected ECParameterSpec for agreement algorithm " + agreementAlg + ", found " + params.getClass().getCanonicalName());
    } else if ("DH".equals(agreementAlg) && !(params instanceof DHParameterSpec)) {
      throw new IllegalArgumentException("Expected DHParameterSpec for agreement algorithm " + agreementAlg + ", found " + params.getClass().getCanonicalName());
    }
    
    Cipher staticPACECipher = null;
    try {
      staticPACECipher = Cipher.getInstance(cipherAlg + "/CBC/NoPadding");
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side error in static cipher construction during key derivation step");
    }
    
    try {
      
      /* FIXME: multiple domain params feature not implemented here, for now. */
      byte[] referencePrivateKeyOrForComputingSessionKey = null;
      
      /* Send to the PICC. */
      service.sendMSESetATMutualAuth(wrapper, oid, PassportApduService.MRZ_PACE_KEY_REFERENCE, referencePrivateKeyOrForComputingSessionKey);
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side error in static PACE key derivation step", cse.getSW());
    }
    
    /*
     * PCD and PICC exchange a chain of general authenticate commands.
     * Steps 1 to 4 below correspond with steps in table 3.3 of
     * ICAO TR-SAC 1.01.
     */
    
    /*
     * Receive encrypted nonce z = E(K_pi, s).
     * Decrypt nonce s = D(K_pi, z).
     */
    byte[] piccNonce = doPACEStep1(staticPACEKey, staticPACECipher);
    
    /*
     * Receive additional data required for map (i.e. a public key from PICC, and (conditionally) a nonce t).
     * Compute ephemeral domain parameters D~ = Map(D_PICC, s).
     */
    AlgorithmParameterSpec ephemeralParams = doPACEStep2(mappingType, agreementAlg, params, piccNonce);
    
    /* Choose random ephemeral PCD side keys (SK_PCD~, PK_PCD~, D~). */
    KeyPair pcdKeyPair = doPACEStep3GenerateKeyPair(agreementAlg, ephemeralParams);
    
    /*
     * Exchange PK_PCD~ and PK_PICC~ with PICC.
     * Check that PK_PCD~ and PK_PICC~ differ.
     */
    PublicKey piccPublicKey = doPACEStep3ExchangePublicKeys(pcdKeyPair.getPublic(), ephemeralParams);
    
    /* Key agreement K = KA(SK_PCD~, PK_PICC~, D~). */
    byte[] sharedSecretBytes = doPACEStep3KeyAgreement(agreementAlg, pcdKeyPair.getPrivate(), piccPublicKey);
    
    /* Derive secure messaging keys. */
    /* Compute session keys K_mac = KDF_mac(K), K_enc = KDF_enc(K). */    
    SecretKey encKey = null;
    SecretKey macKey = null;
    try {
      encKey = Util.deriveKey(sharedSecretBytes, cipherAlg, keyLength, Util.ENC_MODE);
      macKey = Util.deriveKey(sharedSecretBytes, cipherAlg, keyLength, Util.MAC_MODE);
    } catch (GeneralSecurityException gse) {
      LOGGER.log(Level.SEVERE, "Security exception during secure messaging key derivation", gse);
      throw new PACEException("Security exception during secure messaging key derivation: " + gse.getMessage());
    }
    
    /*
     * Compute authentication token T_PCD = MAC(K_mac, PK_PICC~).
     * Exchange authentication token T_PCD and T_PICC with PICC.
     * Check authentication token T_PICC.
     * 
     * Extract encryptedChipAuthenticationData, if mapping is CAM.
     */
    byte[] encryptedChipAuthenticationData = doPACEStep4(oid, mappingType, pcdKeyPair, piccPublicKey, macKey);
    byte[] chipAuthenticationData = null;
    
    /*
     * Start secure messaging.
     *
     * 4.6 of TR-SAC: If Secure Messaging is restarted, the SSC is used as follows:
     *  - The commands used for key agreement are protected with the old session keys and old SSC.
     *    This applies in particular for the response of the last command used for session key agreement.
     *  - The Send Sequence Counter is set to its new start value, i.e. within this specification the SSC is set to 0.
     *  - The new session keys and the new SSC are used to protect subsequent commands/responses.
     */
    try {
      if (cipherAlg.startsWith("DESede")) {
        wrapper = new DESedeSecureMessagingWrapper(encKey, macKey);
      } else if (cipherAlg.startsWith("AES")) {
        long ssc = wrapper == null ? 0L : wrapper.getSendSequenceCounter();
        wrapper = new AESSecureMessagingWrapper(encKey, macKey, ssc);
      }
    } catch (GeneralSecurityException gse) {
      LOGGER.severe("Exception: " + gse.getMessage());
      throw new IllegalStateException("Security exception in secure messaging establishment: " + gse.getMessage());
    }
    
    if (MappingType.CAM.equals(mappingType)) {
      
      if (encryptedChipAuthenticationData == null) {
        LOGGER.severe("Encrypted Chip Authentication data is null");
      }
            
      /* Decrypt A_PICC to recover CA_PICC. */
      try {
        SecretKey secretKey = encKey; // new SecretKeySpec(sharedSecretBytes, "AES");
        Cipher decryptCipher = Cipher.getInstance("AES/CBC/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(IV_FOR_PACE_CAM_DECRYPTION));
        chipAuthenticationData = Util.unpad(decryptCipher.doFinal(encryptedChipAuthenticationData));
        
        LOGGER.info("DEBUG: Including Chip Authentication data in PACE result");
        
      } catch (GeneralSecurityException gse) {
        LOGGER.log(Level.SEVERE, "Could not decrypt Chip Authentication data", gse);
      }
    }
    
    return new PACEResult(mappingType, agreementAlg, cipherAlg, digestAlg, keyLength,
        params,
        piccNonce, ephemeralParams, pcdKeyPair, piccPublicKey, sharedSecretBytes, encryptedChipAuthenticationData, chipAuthenticationData, wrapper);
  }
  
  /**
   * The first step in the PACE protocol receives an encrypted nonce from the PICC
   * and decrypts it.
   * 
   * @param staticPACEKey the static PACE key
   * @param staticPACECipher the cipher to reuse
   * 
   * @return the decrypted encrypted PICC nonce
   * 
   * @throws PACEException on error
   */
  /*
   * 1. Encrypted Nonce     - --- Absent        - 0x80 Encrypted Nonce
   *
   * Receive encrypted nonce z = E(K_pi, s).
   * (This is steps 1-3 in Table 4.4 in BSI 03111 2.0.)
   *
   * Decrypt nonce s = D(K_pi, z).
   * (This is step 4 in Table 4.4 in BSI 03111 2.0.)
   */
  public byte[] doPACEStep1(SecretKey staticPACEKey, Cipher staticPACECipher) throws PACEException {
    byte[] piccNonce = null;
    try {
      byte[] step1Data = new byte[] { };
      /* Command data is empty. this implies an empty dynamic authentication object. */
      byte[] step1Response = service.sendGeneralAuthenticate(wrapper, step1Data, false);
      byte[] step1EncryptedNonce = Util.unwrapDO((byte)0x80, step1Response);
      
      /* (Re)initialize the K_pi cipher for decryption. */
      
      //      staticPACECipher.init(Cipher.DECRYPT_MODE, staticPACEKey, new IvParameterSpec(new byte[16])); /* FIXME: iv length 16 is independent of keylength? */
      //      staticPACECipher.init(Cipher.DECRYPT_MODE, staticPACEKey, new IvParameterSpec(new byte[step1EncryptedNonce.length])); // Fix proposed by Dorian ALADEL (dorian.aladel@gemalto.com)
      staticPACECipher.init(Cipher.DECRYPT_MODE, staticPACEKey, new IvParameterSpec(new byte[staticPACECipher.getBlockSize()])); // Fix proposed by Halvdan Grelland (halvdanhg@gmail.com)
      
      piccNonce = staticPACECipher.doFinal(step1EncryptedNonce);
      return piccNonce;
    } catch (GeneralSecurityException gse) {
      LOGGER.severe("Exception: " + gse.getMessage());
      throw new PACEException("PCD side exception in tranceiving nonce step: " + gse.getMessage());
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side exception in tranceiving nonce step", cse.getSW());
    }
  }
  
  /**
   * The second step in the PACE protocol computes ephemeral domain parameters
   * by performing a key agreement protocol with the PICC nonce as
   * input.
   * 
   * @param mappingType either CAM, GM, or IM
   * @param agreementAlg the agreement algorithm, either DH or ECDH
   * @param params the static domain parameters
   * @param piccNonce the received nonce from the PICC
   * 
   * @return the computed ephemeral domain parameters
   * 
   * @throws PACEException on error
   */
  /*
   * 2. Map Nonce       - 0x81 Mapping Data     - 0x82 Mapping Data
   *
   * (This is step 3.a) in the protocol in TR-SAC.)
   * (This is step 5 in Table 4.4 in BSI 03111 2.0.)
   *
   * Receive additional data required for map (i.e. a public key from PICC, and (conditionally) a nonce t).
   * Compute ephemeral domain parameters D~ = Map(D_PICC, s).
   */
  public AlgorithmParameterSpec doPACEStep2(MappingType mappingType, String agreementAlg, AlgorithmParameterSpec params, byte[] piccNonce) throws PACEException {
    switch(mappingType) {
      case CAM:
        /* NOTE: Fall through to GM case. */
      case GM:
        return doPACEStep2GM(agreementAlg, params, piccNonce);
      case IM:
        return doPACEStep2IM(agreementAlg, params, piccNonce);
      default:
        throw new PACEException("Unsupported mapping type " + mappingType);
    }
  }
  
  /**
   * The second step in the PACE protocol computes ephemeral domain parameters
   * by performing a key agreement protocol with the PICC nonce as
   * input.
   * 
   * @param agreementAlg the agreement algorithm, either DH or ECDH
   * @param params the static domain parameters
   * @param piccNonce the received nonce from the PICC
   * 
   * @return the computed ephemeral domain parameters
   * 
   * @throws PACEException on error
   */
  public AlgorithmParameterSpec doPACEStep2GM(String agreementAlg, AlgorithmParameterSpec params, byte[] piccNonce) throws PACEException {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(agreementAlg, BC_PROVIDER);
      keyPairGenerator.initialize(params);
      KeyPair kp = keyPairGenerator.generateKeyPair();
      PublicKey pcdMappingPublicKey = kp.getPublic();
      PrivateKey pcdMappingPrivateKey = kp.getPrivate();
      KeyAgreement mappingAgreement = KeyAgreement.getInstance(agreementAlg);
      mappingAgreement.init(pcdMappingPrivateKey);
      
      /* DEBUG */
      MyECDHKeyAgreement myECDHKeyAgreement = null;
      if ("ECDH".equals(agreementAlg)) {
        myECDHKeyAgreement = new MyECDHKeyAgreement();
        myECDHKeyAgreement.init((ECPrivateKey)pcdMappingPrivateKey);
      }
      /* END DEBUG */
      
      byte[] pcdMappingEncodedPublicKey = Util.encodePublicKeyForSmartCard(pcdMappingPublicKey);            
      byte[] step2Data = Util.wrapDO((byte)0x81, pcdMappingEncodedPublicKey);
      byte[] step2Response = service.sendGeneralAuthenticate(wrapper, step2Data, false);
      byte[] piccMappingEncodedPublicKey = Util.unwrapDO((byte)0x82, step2Response);
      PublicKey piccMappingPublicKey = Util.decodePublicKeyFromSmartCard(piccMappingEncodedPublicKey, params);
      mappingAgreement.doPhase(piccMappingPublicKey, true);
      
      byte[] mappingSharedSecretBytes = mappingAgreement.generateSecret();
      
      LOGGER.info("DEBUG: mappingSharedSecretBytes = " + Hex.bytesToHexString(mappingSharedSecretBytes));
      
      if ("ECDH".equals(agreementAlg) && myECDHKeyAgreement != null) {
        /* DEBUG */
        /* Treat shared secret as an ECPoint. */
        ECPoint sharedSecretPointH = myECDHKeyAgreement.doPhase((ECPublicKey)piccMappingPublicKey);
        LOGGER.info("DEBUG: calling mapNonceGMWithECDH directly");
        LOGGER.info("DEBUG: Affine X = " + sharedSecretPointH.getAffineX());
        LOGGER.info("DEBUG: Affine Y = " + sharedSecretPointH.getAffineY());
        return Util.mapNonceGMWithECDH(Util.os2i(piccNonce), sharedSecretPointH, (ECParameterSpec)params);
        /* END DEBUG */
      } else if ("DH".equals(agreementAlg)) {
        return Util.mapNonceGMWithDH(Util.os2i(piccNonce), Util.os2i(mappingSharedSecretBytes), (DHParameterSpec)params);
      } else {
        throw new IllegalArgumentException("Unsupported parameters for mapping nonce, expected ECParameterSpec or DHParameterSpec, found " + params.getClass().getCanonicalName());
      }
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side error in mapping nonce step: " + gse.getMessage());
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side exception in mapping nonce step", cse.getSW());
    }
  }
  
  /**
   * The second step in the PACE protocol computes ephemeral domain parameters
   * by performing a key agreement protocol with the PICC nonce as
   * input.
   * 
   * @param agreementAlg the agreement algorithm, either DH or ECDH
   * @param params the static domain parameters
   * @param piccNonce the received nonce from the PICC
   * 
   * @return the computed ephemeral domain parameters
   * 
   * @throws PACEException on error
   */
  public AlgorithmParameterSpec doPACEStep2IM(String agreementAlg, AlgorithmParameterSpec params, byte[] piccNonce) throws PACEException {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(agreementAlg, BC_PROVIDER);
      keyPairGenerator.initialize(params);
      KeyPair kp = keyPairGenerator.generateKeyPair();
      PublicKey pcdMappingPublicKey = kp.getPublic();
      PrivateKey pcdMappingPrivateKey = kp.getPrivate();
      KeyAgreement mappingAgreement = KeyAgreement.getInstance(agreementAlg);
      mappingAgreement.init(pcdMappingPrivateKey);
      
      byte[] pcdNonce = new byte[piccNonce.length];
      random.nextBytes(pcdNonce);
      
      pseudoRandomFunction(piccNonce, pcdNonce, null);
      /* NOTE: The context specific data object 0x82 SHALL be empty (TR SAC 3.3.2). */
      
      throw new PACEException("Integrated Mapping not yet implemented"); // FIXME
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side error in mapping nonce step: " + gse.getMessage());
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side exception in mapping nonce step", cse.getSW());
    }
  }
  
  private void pseudoRandomFunction(byte[] piccNonce, byte[] pcdNonce, Cipher cipher) {
    // TODO Auto-generated method stub
    
  }
  
  /* Choose random ephemeral key pair (SK_PCD~, PK_PCD~, D~). */
  public KeyPair doPACEStep3GenerateKeyPair(String agreementAlg, AlgorithmParameterSpec ephemeralParams) throws PACEException {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(agreementAlg, BC_PROVIDER);
      keyPairGenerator.initialize(ephemeralParams);
      KeyPair kp = keyPairGenerator.generateKeyPair();
      return kp;
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side error during generation of PCD key pair");
    }
  }
  
  /*
   * 3. Perform Key Agreement - 0x83 Ephemeral Public Key - 0x84 Ephemeral Public Key
   *
   * Exchange PK_PCD~ and PK_PICC~ with PICC.
   * Check that PK_PCD~ and PK_PICC~ differ.
   */
  public PublicKey doPACEStep3ExchangePublicKeys(PublicKey pcdPublicKey, AlgorithmParameterSpec ephemeralParams)  throws PACEException {    
    try {
      byte[] pcdEncodedPublicKey = Util.encodePublicKeyForSmartCard(pcdPublicKey);
      byte[] step3Data = Util.wrapDO((byte)0x83, pcdEncodedPublicKey);
      byte[] step3Response = service.sendGeneralAuthenticate(wrapper, step3Data, false);
      byte[] piccEncodedPublicKey = Util.unwrapDO((byte)0x84, step3Response);
      PublicKey piccPublicKey = Util.decodePublicKeyFromSmartCard(piccEncodedPublicKey, ephemeralParams);
      
      if (pcdPublicKey.equals(piccPublicKey)) {
        throw new PACEException("PCD's public key and PICC's public key are the same in key agreement step!");
      }
      
      return piccPublicKey;
    } catch (IllegalStateException ise) {
      throw new PACEException("PCD side exception in key agreement step: " + ise.getMessage());
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side exception in key agreement step: " + gse.getMessage());
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side exception in key agreement step", cse.getSW());
    }
  }
  
  /* Key agreement K = KA(SK_PCD~, PK_PICC~, D~). */
  public byte[] doPACEStep3KeyAgreement(String agreementAlg, PrivateKey pcdPrivateKey, PublicKey piccPublicKey) throws PACEException {
    try {
      KeyAgreement keyAgreement = KeyAgreement.getInstance(agreementAlg, BC_PROVIDER);
      keyAgreement.init(pcdPrivateKey);
      keyAgreement.doPhase(updateParameterSpec(piccPublicKey, pcdPrivateKey), true);
      return keyAgreement.generateSecret();
    } catch (GeneralSecurityException gse) {
      LOGGER.log(Level.SEVERE, "PCD side error during key agreement", gse);
      throw new PACEException("PCD side error during key agreement");
    }
  }
  
  /*
   * 4. Mutual Authentication - 0x85 Authentication Token - 0x86 Authentication Token
   *
   * Compute authentication token T_PCD = MAC(K_mac, PK_PICC~).
   * Exchange authentication token T_PCD and T_PICC with PICC.
   * Check authentication token T_PICC.
   * 
   * Extracts encryptedChipAuthenticationData, if mapping type id CAM.
   */
  public byte[] doPACEStep4(String oid, MappingType mappingType, KeyPair pcdKeyPair, PublicKey piccPublicKey, SecretKey macKey) throws PACEException {
    try {
      byte[] pcdToken = generateAuthenticationToken(oid, macKey, piccPublicKey);
      byte[] step4Data = Util.wrapDO((byte)0x85, pcdToken);
      byte[] step4Response = service.sendGeneralAuthenticate(wrapper, step4Data, true);
      byte[] piccToken = Util.unwrapDO((byte)0x86, step4Response);
      byte[] expectedPICCToken = generateAuthenticationToken(oid, macKey, pcdKeyPair.getPublic());
      if (!Arrays.equals(expectedPICCToken, piccToken)) {
        throw new GeneralSecurityException("PICC authentication token mismatch");
      }
      
      if (mappingType == MappingType.CAM) {
        return Util.unwrapDO((byte)0x8A, step4Data);
      } else {
        return null;
      }
    } catch (GeneralSecurityException gse) {
      throw new PACEException("PCD side exception in authentication token generation step: " + gse.getMessage());
    } catch (CardServiceException cse) {
      throw new PACEException("PICC side exception in authentication token generation step", cse.getSW());
    }
  }
  
  /**
   * Derives the static key K_pi.
   * 
   * @param keySpec the key material from the MRZ
   * @param oid the PACE object identifier is needed to determine the cipher algorithm and the key length
   * 
   * @return the derived key
   * 
   * @throws GeneralSecurityException on error
   */
  public static SecretKey deriveStaticPACEKey(KeySpec keySpec, String oid) throws GeneralSecurityException {
    String cipherAlg  = PACEInfo.toCipherAlgorithm(oid); /* Either DESede or AES. */
    int keyLength = PACEInfo.toKeyLength(oid); /* Of the enc cipher. Either 128, 192, or 256. */
    byte[] keySeed = computeKeySeedForPACE(keySpec);
    return Util.deriveKey(keySeed, cipherAlg, keyLength, Util.PACE_MODE);
  }
  
  public static byte[] computeKeySeedForPACE(KeySpec keySpec) throws GeneralSecurityException {
    if (!(keySpec instanceof BACKeySpec)) {
      throw new IllegalArgumentException("Unsupported key spec, was expecting BAC key spec");
    }
    
    BACKeySpec bacKey = (BACKeySpec)keySpec;
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
    
    byte[] keySeed = computeKeySeedForPACE(documentNumber, dateOfBirth, dateOfExpiry);
    
    return keySeed;
  }
  
  /**
   * Updates the parameters of the given public key to match the parameters of the given private key.
   * 
   * @param publicKey the public key, should be an EC public key
   * @param privateKey the private key, should be an EC private key
   * 
   * @return a new public key that uses the parameters of the private key
   * 
   * @throws GeneralSecurityException on security error, or when keys are not EC
   */
  public static PublicKey updateParameterSpec(PublicKey publicKey, PrivateKey privateKey) throws GeneralSecurityException {
    if (!(publicKey instanceof ECPublicKey) || !(privateKey instanceof ECPrivateKey)) {
      throw new NoSuchAlgorithmException("Unsupported key type");
    }

    KeyFactory keyFactory = KeyFactory.getInstance("EC");    
    KeySpec keySpec = new ECPublicKeySpec(((ECPublicKey)publicKey).getW(), ((ECPrivateKey)privateKey).getParams());
    return keyFactory.generatePublic(keySpec);
  }
  
  /**
   * Computes the static key seed to be used in PACE KDF, based on information from the MRZ.
   *
   * @param documentNumber a string containing the document number
   * @param dateOfBirth a string containing the date of birth (YYMMDD)
   * @param dateOfExpiry a string containing the date of expiry (YYMMDD)
   *
   * @return a byte array of length 16 containing the key seed
   *
   * @throws GeneralSecurityException on security error
   */
  private static byte[] computeKeySeedForPACE(String documentNumber, String dateOfBirth, String dateOfExpiry) throws GeneralSecurityException {
    return Util.computeKeySeed(documentNumber, dateOfBirth, dateOfExpiry, "SHA-1", false);
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
  
  /**
   * The authentication token SHALL be computed over a public key data object (cf. Section 4.5)
   * containing the object identifier as indicated in MSE:Set AT (cf. Section 3.2.1), and the
   * received ephemeral public key (i.e. excluding the domain parameters, cf. Section 4.5.3)
   * using an authentication code and the key KS MAC derived from the key agreement.
   *
   * @param oid the object identifier as indicated in MSE Set AT
   * @param macKey the KS MAC key derived from the key agreement
   * @param publicKey the received public key
   *
   * @return the authentication code
   *
   * @throws GeneralSecurityException on error while performing the MAC operation
   */
  public static byte[] generateAuthenticationToken(String oid, SecretKey macKey, PublicKey publicKey) throws GeneralSecurityException {
    String cipherAlg = PACEInfo.toCipherAlgorithm(oid);
    String macAlg = inferMacAlgorithmFromCipherAlgorithm(cipherAlg);
    Mac mac = Mac.getInstance(macAlg, BC_PROVIDER);
    byte[] encodedPublicKeyDataObject = Util.encodePublicKeyDataObject(oid, publicKey);
    mac.init(macKey);
    byte[] maccedPublicKeyDataObject = mac.doFinal(encodedPublicKeyDataObject);
    
    /* Output length needs to be 64 bits, copy first 8 bytes. */
    byte[] authenticationToken = new byte[8];
    System.arraycopy(maccedPublicKeyDataObject, 0, authenticationToken, 0, authenticationToken.length);
    return authenticationToken;
  }
  
  private static String inferMacAlgorithmFromCipherAlgorithm(String cipherAlg) throws InvalidAlgorithmParameterException {
    if (cipherAlg == null) {
      throw new IllegalArgumentException("Cannot infer MAC algorithm from cipher algorithm null");
    }
    
    /*
     * NOTE: AESCMAC will generate 128 bit (16 byte) results, not 64 bit (8 byte),
     * both authentication token generation and secure messaging,
     * where the Mac is applied, will copy only the first 8 bytes. -- MO
     */
    if (cipherAlg.startsWith("DESede")) {
      /* FIXME: Is macAlg = "ISO9797Alg3Mac" equivalent to macAlg = "DESedeMac"??? - MO */
      return "ISO9797Alg3Mac";
    } else if (cipherAlg.startsWith("AES")) {
      return "AESCMAC";
    } else {
      throw new InvalidAlgorithmParameterException("Cannot infer MAC algorithm from cipher algorithm \"" + cipherAlg + "\"");
    }
  }
  
  public class MyECDHKeyAgreement {
    
    private ECPrivateKey privateKey;
    
    public void init(ECPrivateKey privateKey) {
      this.privateKey = privateKey;
    }
    
    public ECPoint doPhase(ECPublicKey publicKey) {
      ECPublicKeyParameters pub = Util.toBouncyECPublicKeyParameters(publicKey);
      
      org.bouncycastle.math.ec.ECPoint p = pub.getQ().multiply(Util.toBouncyECPrivateKeyParameters(privateKey).getD()).normalize();
      if (p.isInfinity()) {
        throw new IllegalStateException("Infinity");
      }
      return Util.fromBouncyCastleECPoint(p);
    }
  }
}
