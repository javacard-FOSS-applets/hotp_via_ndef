/*
 * ndefOtpGenerator: JavaCard applet generating one time passwords communicating 
 * using NDEF.
 * Based on javacard-ndef by Ingo Albrecht (prom@berlin.ccc.de)
 * Copyright (C) 2015 Ingo Albrecht (prom@berlin.ccc.de)
 *           (C) 2017 CROCS, Faculty of Informatics, Masaryk University (crocs.muni.cz)
 *           (C) 2017 Tomáš Zelina (xzelina1@fi.muni.cz)
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 */

package cz.muni.fi.crocs.javacard.ndefOtpGenerator;

import javacard.framework.*;

/**
 * \brief Applet implementing an NDEF type 4 tag
 *
 * Implemented to comply with:
 *   NFC Forum
 *   Type 4 Tag Operation Specification
 *   Version 2.0
 *
 * Conformity remarks:
 *   1. The NDEF data file can be up to 32767 bytes in size,
 *      corresponding to the specification maximum.
 *   2. No file control information (FCI) is returned in SELECT responses
 *      as allowed by specification requirement RQ_T4T_NDA_034.
 *   3. Proprietary access modes are being used for custom features,
 *      however they are not exposed in the capability descriptor.
 *   4. Proprietary files are not being used.
 *
 */
public final class NdefApplet extends Applet {

    /* Instructions */
    static final byte INS_SELECT        = ISO7816.INS_SELECT;
    static final byte INS_READ_BINARY   = (byte)0xB0;
    static final byte INS_UPDATE_BINARY = (byte)0xD6;

    /* File IDs */
    static final short FILEID_NONE              = (short)0x0000;
    static final short FILEID_NDEF_CAPABILITIES = (short)0xE103;
    static final short FILEID_NDEF_DATA         = (short)0xE104;

    /* File access specifications */
    static final byte FILE_ACCESS_OPEN = (byte)0x00;
    static final byte FILE_ACCESS_NONE = (byte)0xFF;
    static final byte FILE_ACCESS_PROP_CONTACT_ONLY = (byte)0xF0;
    static final byte FILE_ACCESS_PROP_WRITE_ONCE   = (byte)0xF1;

    /* Parameters for SELECT */
    static final byte SELECT_P1_BY_FILEID     = (byte)0x00;
    static final byte SELECT_P2_FIRST_OR_ONLY = (byte)0x0C;

    /* NDEF mapping version (specification 2.0) */
    static final byte NDEF_MAPPING_VERSION = (byte)0x20;

    /* Application data tags */
    static final byte AD_TAG_NDEF_DATA_INITIAL = (byte)0x10;
    static final byte AD_TAG_NDEF_DATA_ACCESS  = (byte)0x11;
    static final byte AD_TAG_NDEF_DATA_SIZE    = (byte)0x12;

    /* Constants related to capability container */
    static final byte CC_LEN_HEADER = 7;
    static final byte CC_OFF_NDEF_FILE_CONTROL = 0x07;
    static final byte CC_TAG_NDEF_FILE_CONTROL = 0x04;
    static final byte CC_LEN_NDEF_FILE_CONTROL = 6;

    /* Constants related to file control data in capabilities */
    static final byte FC_OFF_FILE_ID      = 0x00;
    static final byte FC_OFF_SIZE         = 0x02;
    static final byte FC_OFF_READ_ACCESS  = 0x04;
    static final byte FC_OFF_WRITE_ACCESS = 0x05;

    /**
     * Configuration: support for writing
     *
     * If disabled then no writing will be allowed after
     * initialization. Intended for use in combination
     * with install parameters.
     */
    static final boolean FEATURE_WRITING = true;

    /**
     * Configuration: support for install parameters
     *
     * If enabled this will allow customization of the applet
     * during installation by using application parameters.
     *
     * Disabling this saves about 200 bytes.
     */
    static final boolean FEATURE_INSTALL_PARAMETERS = true;

    /**
     * Configuration: maximum read block size
     */
    static final short NDEF_MAX_READ = 128;

    /**
     * Configuration: maximum write block size
     */
    static final short NDEF_MAX_WRITE = 128;

    /**
     * Configuration: maximum size of data file
     *
     * Two bytes are used for the record length,
     * the rest will be available for an NDEF record.
     */
    static final short DEFAULT_NDEF_DATA_SIZE = 256;

    /**
     * Configuration: read access
     */
    static final byte DEFAULT_NDEF_READ_ACCESS = FILE_ACCESS_OPEN;

    /**
     * Configuration: write access
     */
    static final byte DEFAULT_NDEF_WRITE_ACCESS = FILE_ACCESS_OPEN;


    /** ID of currently selected file */
    private short selectedFile;

    /** NDEF capability file contents */
    private byte[] ndefCaps;
    /** NDEF data file contents */
    private byte[] ndefData;
    
    private byte[] toReturn;

    private byte[] NDEFtypes;
    private short toReturnBytes;
    private Object[] payloads;
    // Currently shown message. (OK, error...). If null, standard HOTP payload is used. Reseted on every SELECT command to null.
    private byte[] messageShown;
    private byte payloadCount;
    
    
    private CodeGenerator counter;
    private AuthProvider lock;
    
    
    private boolean locked;
    
    // Set to true on every write operation, set to false on parse.
    private boolean changedSinceLastParse;
    
    // NDEF-endoded "OK" and "e~~~~" messages
    private static final byte[] okData = {0x00, 0x07, (byte)0xD1, 0x01, 0x03, 0x54, 0x00, 0x4F, 0x4B};
    private byte[] errData = {0x00, 0x0A, (byte)0xD1, 0x01, 0x06, 0x54, 0x00, 0x65, 0x7E, 0x7E, 0x7E, 0x7E};
    private byte[] shortBuffer = new byte[2];
    
    // Pseudo-strings
    private static final byte[] hotpURLIdent  = { 'o', 't', 'p', 'a', 'u', 't', 'h', ':', '/', '/', 'h', 'o', 't', 'p', '/'};
    private static final byte[] hotpLockIdent = { 'o', 't', 'p', 'l', 'o', 'c', 'k', ':', '/', '/', 'h', 'o', 't', 'p', '/'};
    private static final byte[] pwdLockIdent = { 'p', 'w', 'd', 'l', 'o', 'c', 'k', ':', '/', '/'};
    private static final byte[] hardLockIdent = { 'h', 'a', 'r', 'd', 'l', 'o', 'c', 'k', ':', '/', '/'};
    private static final byte[] cardCommandUnlockIdent = { 'c', 'c', ':', '/', '/', 'u', 'n', 'l', 'o', 'c', 'k', '/'};
    private static final byte[] cardCommandIdent = { 'c', 'c', ':', '/', '/'};
    private static final byte[] secretInASCII = {'s', 'e', 'c', 'r', 'e', 't', '='};
    private static final byte[] digitsInASCII = {'d', 'i', 'g', 'i', 't', 's', '='};
    
    private static final byte[] emptyTextPayload = {'\0'};
    /** NDEF data read access policy */
    private byte ndefReadAccess;
    /** NDEF data write access policy */
    private byte ndefWriteAccess;
    /**
     * Installs an NDEF applet
     *
     * Will create, initialize and register an instance of
     * this applet as specified by the provided install data.
     *
     * Requested AID will always be honored.
     * Control information is ignored.
     * Applet data will be used for initialization.
     *
     * @param buf containing install data
     * @param off offset of install data in buf
     * @param len length of install data in buf
     */
    public static void install(byte[] buf, short off, byte len) {
        short pos = off;
        // find AID
        byte  lenAID = buf[pos++];
        short offAID = pos;
        pos += lenAID;
        // find control information (ignored)
        byte  lenCI = buf[pos++];
        short offCI = pos;
        pos += lenCI;
        // find applet data
        byte  lenAD = buf[pos++];
        short offAD = pos;
        pos += lenAD;

        // instantiate and initialize the applet
        NdefApplet applet = new NdefApplet(buf, offAD, lenAD);

        // register the applet
        applet.register(buf, offAID, lenAID);
    }

    /**
     * Main constructor
     *
     * This will construct and initialize an instance
     * of this applet according to the provided app data.
     *
     * @param buf containing application data
     * @param off offset of app data in buf
     * @param len length of app data in buf
     */
    protected NdefApplet(byte[] buf, short off, byte len) {

        short dataSize = DEFAULT_NDEF_DATA_SIZE;
        byte dataReadAccess = DEFAULT_NDEF_READ_ACCESS;
        byte dataWriteAccess = DEFAULT_NDEF_WRITE_ACCESS;
        byte[] initBuf = null;
        short  initOff = 0;
        short  initLen = 0;

        // process application data
        if(FEATURE_INSTALL_PARAMETERS) {
            // check TLV consistency
            if (!UtilTLV.isTLVconsistent(buf, off, len)) {
                ISOException.throwIt(ISO7816.SW_DATA_INVALID);
            }

            // DATA INITIAL
            short initTag = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_INITIAL);
            if (initTag >= 0) {
                initBuf = buf;
                initLen = UtilTLV.decodeLengthField(buf, (short) (initTag + 1));
                initOff = (short) (initTag + 1 + UtilTLV.getLengthFieldLength(initLen));
                // restrict writing, can be overridden
                dataWriteAccess = FILE_ACCESS_NONE;
                // adjust size, can be overridden
                dataSize = (short) (2 + initLen);
            }

            // DATA ACCESS
            short tagAccess = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_ACCESS);
            if (tagAccess >= 0) {
                short accessLen = UtilTLV.decodeLengthField(buf, (short) (tagAccess + 1));
                if (accessLen != 2) {
                    ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                }
                dataReadAccess = buf[(short) (tagAccess + 2)];
                dataWriteAccess = buf[(short) (tagAccess + 3)];
            }

            // DATA SIZE
            short tagSize = UtilTLV.findTag(buf, off, len, AD_TAG_NDEF_DATA_SIZE);
            if (tagSize >= 0) {
                short sizeLen = UtilTLV.decodeLengthField(buf, (short) (tagSize + 1));
                if (sizeLen != 2) {
                    ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                }
                dataSize = Util.getShort(buf, (short) (tagSize + 2));
                if (dataSize < 0) {
                    ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                }
            }
        }

        // squash write access if not supported
        if(!FEATURE_WRITING) {
            dataWriteAccess = FILE_ACCESS_NONE;
        }

        // set up access
        ndefReadAccess = dataReadAccess;
        ndefWriteAccess = dataWriteAccess;

        // create file contents
        ndefCaps = makeCaps(dataSize, dataReadAccess, dataWriteAccess);
        ndefData = makeData(dataSize, initBuf, initOff, initLen);
        
        changedSinceLastParse = false;
        toReturn = new byte[DEFAULT_NDEF_DATA_SIZE];
        counter = new Counter();
        payloads = new Object[10];
        NDEFtypes = new byte[10];
        locked = false;
        payloads[0] = emptyTextPayload;
        payloadCount = 1;
        NDEFtypes[0] = (byte) 0x54; //Text
    }

    /**
     * Create and initialize the CAPABILITIES file
     *
     * @param dataSize to be allocated
     * @param dataReadAccess to put in the CC
     * @param dataWriteAccess to put in the CC
     * @return an array for use as the CC file
     */
    private byte[] makeCaps(short dataSize,
                            byte dataReadAccess, byte dataWriteAccess) {
        short capsLen = (short)(CC_LEN_HEADER + 2 + CC_LEN_NDEF_FILE_CONTROL);
        byte[] caps = new byte[capsLen];

        short pos = 0;

        // CC length
        pos = Util.setShort(caps, pos,  capsLen);
        // mapping version
        caps[pos++] = NDEF_MAPPING_VERSION;
        // maximum read size
        pos = Util.setShort(caps, pos, NDEF_MAX_READ);
        // maximum write size
        pos = Util.setShort(caps, pos, NDEF_MAX_WRITE);

        // NDEF File Control TLV
        caps[pos++] = CC_TAG_NDEF_FILE_CONTROL;
        caps[pos++] = CC_LEN_NDEF_FILE_CONTROL;
        // file ID
        pos = Util.setShort(caps, pos, FILEID_NDEF_DATA);
        // file size
        pos = Util.setShort(caps, pos, dataSize);
        // read access
        caps[pos++] = dataReadAccess;
        // write access
        caps[pos++] = dataWriteAccess;

        // check consistency
        if(pos != capsLen) {
            ISOException.throwIt(ISO7816.SW_UNKNOWN);
        }

        // return the file
        return caps;
    }

    /**
     * Create and initialize the DATA file
     *
     * @param dataSize to be allocated
     * @param init buffer containing initial data
     * @param initOff offset of initial data in buffer
     * @param initLen length of initial data in buffer
     * @return an array for use as the data file
     */
    private byte[] makeData(short dataSize, byte[] init, short initOff, short initLen) {
        byte[] data = new byte[dataSize];

        // initialize from init, if provided
        if (FEATURE_INSTALL_PARAMETERS) {
            if (init != null && initLen > 0) {
                // container size
                Util.setShort(data, (short) 0, initLen);
                // initial data
                Util.arrayCopyNonAtomic(init, initOff, data, (short) 2, initLen);
            }
        }

        return data;
    }

    /**
     * Fix up a capability container
     *
     * This will be called to fix up capabilities before
     * they are actually sent out to the host device.
     *
     * Currently this only fixes up the access policies
     * so as to hide our proprietary policies.
     *
     * @param caps buffer containing CC to fix
     * @param off offset of CC in buffer
     * @param len of CC in buffer
     */
    private void fixCaps(byte[] caps, short off, short len) {
        short offNFC = (short)(off + CC_OFF_NDEF_FILE_CONTROL + 2);
        short offR = (short)(offNFC + FC_OFF_READ_ACCESS);
        short offW = (short)(offNFC + FC_OFF_WRITE_ACCESS);
        caps[offR] = fixAccess(ndefData, ndefReadAccess);
        caps[offW] = fixAccess(ndefData, ndefWriteAccess);
    }

    /**
     * Process an APDU
     *
     * This is the outer layer of our APDU dispatch.
     *
     * It deals with the CLA and INS of the APDU,
     * leaving the rest to an INS-specific function.
     *
     * @param apdu to be processed
     * @throws ISOException on error
     */
    @Override
    public void process(APDU apdu) throws ISOException {
        byte[] buffer = apdu.getBuffer();
        byte ins = buffer[ISO7816.OFFSET_INS];

        // handle selection of the applet
        if(selectingApplet()) {
            selectedFile = FILEID_NONE;
            return;
        }

        // secure messaging is not supported
        if(apdu.isSecureMessagingCLA()) {
            ISOException.throwIt(ISO7816.SW_SECURE_MESSAGING_NOT_SUPPORTED);
        }

        // process commands to the applet
        if(apdu.isISOInterindustryCLA()) {
            switch (ins) {
                case INS_SELECT:
                    processSelect(apdu);
                    break;
                case INS_READ_BINARY:
                    processReadBinary(apdu);
                    break;
                case INS_UPDATE_BINARY:
                    if(FEATURE_WRITING) {
                        processUpdateBinary(apdu);
                    } else {
                        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
                    }
                    break;
                default:
                    ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
            }
        } else {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }
    }

    /**
     * Process a SELECT command
     *
     * This handles only the one case mandated by the NDEF
     * specification: SELECT FIRST-OR-ONLY BY-FILE-ID.
     *
     * The file ID is specified in the APDU contents. It
     * must be exactly two bytes long and also valid.
     *
     * @param apdu to process
     * @throws ISOException on error
     */
    private void processSelect(APDU apdu) throws ISOException {
        byte[] buffer = apdu.getBuffer();
        byte p1 = buffer[ISO7816.OFFSET_P1];
        byte p2 = buffer[ISO7816.OFFSET_P2];
        byte lc = buffer[ISO7816.OFFSET_LC];

        // we only support what the NDEF spec prescribes
        if(p1 != SELECT_P1_BY_FILEID) {
            ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }
        if(p2 != SELECT_P2_FIRST_OR_ONLY) {
            ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }

        // check length, must be for a file ID
        if(lc != 2) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // retrieve the file ID
        short fileId = Util.getShort(buffer, ISO7816.OFFSET_CDATA);

        // perform selection if the ID is valid
        switch(fileId) {
            case FILEID_NDEF_CAPABILITIES:
            case FILEID_NDEF_DATA:
                selectedFile = fileId;
                break;
            default:
                ISOException.throwIt(ISO7816.SW_FILE_NOT_FOUND);
        }
        
        // reset shown message
        messageShown = null;
    }
    
    /**
     * Parses recieved ndef messages and changes payload and secret accordingly
     *
     * If array contains URL (TNF == 1 and type is 'U') or text (type 'T'), its
     * saved into payload array, so it can be appended with code from generator.
     * 
     * If payload starts with otpauth://hotp/, new instance of HMACgenerator is
     * created and set as default generator.
     * 
     * Example of optauth url:
     * 
     * otpauth://hotp/username@server?secret=base32endodedsecret&digits=6
     * 
     * (Google authenticator format)
     * 
     * This implementation doesn't accept 'counter' or any other parameter, 
     * counter is always set to 0.
     * 
     * @param data byte array containing recieved ndef message
     * @throws ISOException on error
     */
    private void parseData(byte data[]){
        boolean longRecord = false;
        boolean containsID = false;
        short i = 2;
        short idLen = 0;
        short payloadLen;
        byte firstByte = data[i];
        byte possibleNDEFDataType;
        i++;
        // | MB  | ME  | CF  |  SR  | IL  |       TNF       |
        if ((firstByte & 0x80) != 0x80) { //MB != 1
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }

        //LONG RECORD
        if ((firstByte & 0x10) == 0x00) {
            longRecord = true;
        }
        if ((firstByte & 0x08) == 0x08) {
            containsID = true;
        }
        
        short typeLength = data[i];
        i++;

        if (longRecord) {
            // Max data size is 254 B, so we can safely ignore PAYLOAD_LENGTH_1~3
            i = (short) (i + 3);
        }

        payloadLen = data[i];
        i++;

        if (containsID) {
            idLen = data[i];
            i++;
        }

        // Only 'U' (URL) and 'T' (Text) type records are supported
        if (typeLength != 1 && data[i] != 'U' && data[i] != 'T') {
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
            return;
        } else {
            possibleNDEFDataType = data[i];
        }
        i++;

        if (containsID) {
            i = (short) (i + idLen);
        }
        
        short contentStart = i; //This is index used for parsing purposes
        if(possibleNDEFDataType == 'T'){
            //Text record status byte: 
            //      7   - Encoding - 1: UTF-16, 0: UTF-8
            //      6   - Reserved, should be always 0
            //      5~0 - Length of language code
            if((data[contentStart] & 0x80) != 0){
                ISOException.throwIt(ISO7816.SW_DATA_INVALID); // UTF-16 is not supported, sorry...
            }
            contentStart = (short) (contentStart + data[contentStart] & 0x3F); // Language code
        }
        contentStart++;
        short contentLength = (short) (payloadLen - (contentStart - i));
        
        
        //end of header parsing, payload now starts at i
        short payloadStart = i;
        
        if(UtilByteArray.compareByteArrays(cardCommandUnlockIdent, (short) 0, data, contentStart, (short) cardCommandUnlockIdent.length)){
            if(!locked){
                ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
            }
            contentStart = (short) (contentStart + cardCommandUnlockIdent.length);
            locked = !lock.check(data, contentStart, (short) (contentLength - cardCommandUnlockIdent.length));
            if(locked){
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
            return;
        }
        
        if(locked){
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }

        if(UtilByteArray.compareByteArrays(hotpURLIdent, (short) 0, data, contentStart, (short) hotpURLIdent.length)){
            //This is HOTP URI, process it...
            counter = processHotpURI(data, contentLength, contentStart);
            return;
        }
        
        if(UtilByteArray.compareByteArrays(hotpLockIdent, (short) 0, data, contentStart, (short) hotpLockIdent.length)){
            lock = processHotpURI(data, contentLength, contentStart);
            return;
        }
        
        if(UtilByteArray.compareByteArrays(hardLockIdent, (short) 0, data, contentStart, (short) hardLockIdent.length)){
            lock = new HardLock();
            return;
        }
        
        if(UtilByteArray.compareByteArrays(pwdLockIdent, (short) 0, data, contentStart, (short) pwdLockIdent.length)){
            contentStart = (short) (contentStart + pwdLockIdent.length);
            lock = new PasswordLock(data, contentStart, (short) (contentLength - pwdLockIdent.length));
            return;
        }
        
        if(UtilByteArray.compareByteArrays(cardCommandIdent, (short) 0, data, contentStart, (short) cardCommandIdent.length)){
            processCardCommand(data, contentLength, contentStart);
            return;
        }
        
        // Replace default payload if needed
        if(payloadCount == 1 && payloads[0] == emptyTextPayload){
            payloadCount--;
        }
        payloads[payloadCount] = new byte[payloadLen];
        NDEFtypes[payloadCount] = possibleNDEFDataType;
        Util.arrayCopyNonAtomic(data, payloadStart, (byte[])payloads[payloadCount], (short) 0, payloadLen);
        payloadCount++;
    }
    
    private static final byte[] lockString = {'l', 'o', 'c', 'k'};
    private static final byte[] removeString = {'r', 'e', 'm', 'o', 'v', 'e', '/'};
    
    private void processCardCommand(byte[] data, short payloadLen, short from){
        short index = (short) (from + cardCommandIdent.length);
        if(UtilByteArray.compareByteArrays(lockString, (short) 0, data, index, (short) lockString.length)){
            if(lock == null){
                ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
            }
            locked = true;
            return;
        }
        if(UtilByteArray.compareByteArrays(removeString, (short) 0, data, index, (short) removeString.length)){
            index = (short) (index + removeString.length);
            byte toRemove = UtilBCD.asciiDigitToByte(data[index]);
            if(payloadCount == 1 || toRemove >= payloadCount) {
                ISOException.throwIt(ISO7816.SW_WRONG_DATA);
            }
            payloadCount--;
            for(byte i = toRemove; i < payloadCount; i++){
                payloads[i] = payloads[(byte) (i+1)];
                NDEFtypes[i] = NDEFtypes[(byte) (i+1)];
            }
            
            // TODO: consider using requestObjectDeletion() if card supports it
            // Warning: https://stackoverflow.com/questions/28147582/javacard-power-loss-during-garbage-collection
            // 
            
            return;
        }
        
        
        ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        
    }
    
    private HMACgenerator processHotpURI(byte[] data, short payloadLen, short from ){
        
        // HOTP URI, process
        byte key[] = null;
        short keyLen = 0;
        short startOfPayLoad = from;
        short digits = 6;
        short index = (short) (from + hotpURLIdent.length);
        // skip username
        index = UtilByteArray.findFirstOccurence(data, index, (byte) '?');
        index++;
        
        //process params
        while(index < (short) data.length && index > 0){

            if(UtilByteArray.compareByteArrays(data, index, 
                    secretInASCII, (short) 0, (short)secretInASCII.length)){
                // set secret
                index = (short) (index + secretInASCII.length);
                keyLen = (short) (UtilByteArray.findFirstOccurence(data, index, (byte) '&') - index);
                if(keyLen < (short) 0){
                    keyLen = (short) (payloadLen - (index - startOfPayLoad));
                }
                key = new byte[(short)(2 * keyLen)];
                keyLen = UtilBase32.base32toByteArray(data, index, keyLen, key, (short) 0);


            }else if(UtilByteArray.compareByteArrays(data, index, 
                    digitsInASCII, (short) 0, (short)digitsInASCII.length)){
                index = (short) (index + digitsInASCII.length);
                digits = UtilBCD.asciiDigitToByte(data[index]);
                if (digits == 0){
                    ISOException.throwIt(ISO7816.SW_DATA_INVALID);
                }
            }
            index = (short)(UtilByteArray.findFirstOccurence(data, index, (byte) '&') + 1);
        }
        
        if(key == null || keyLen < 1){
            ISOException.throwIt(ISO7816.SW_DATA_INVALID);
        }
        
        return new HMACgenerator(key, keyLen, digits);
        
    }
    
    private void generateData(short start, byte counterASCII[], byte header, byte payload[], byte NDEFtype){
        // Note: First record starts on index 2, not 0
        toReturn[start] = header;
        toReturn[(byte) (start + 1)] = (byte) 0x01; //Type length is 1
        toReturn[(byte) (start + 2)] = (byte) (payload.length + counterASCII.length); //PayloadLength
        toReturn[(byte) (start + 3)] = NDEFtype; //URL == 0x55, Text == 0x54
        toReturnBytes = (short) (toReturnBytes + 4 + payload.length + counterASCII.length);
        toReturn[0] = (byte) ((toReturnBytes & (short) 0xFF00) >> 8);
        toReturn[1] = (byte) (toReturnBytes & (short) 0x00FF);
        
        Util.arrayCopyNonAtomic(payload, (short) 0, toReturn, (short) (start + 4), (short) payload.length);
        Util.arrayCopyNonAtomic(counterASCII, (short) 0, toReturn, (short) (start + 4 + payload.length), (short) counterASCII.length);
    }
    
    private void generateData(){
        toReturnBytes = 0;
        byte counterASCII[] = counter.getAscii();
        if(payloadCount == 1){
            generateData((short) (toReturnBytes + 2), counterASCII, (byte) 0xD1, (byte[])payloads[0], NDEFtypes[0]);
        } else {
            byte header;
            byte headerFirst  = (byte) 0x91; //1001 0001
            byte headerMiddle = (byte) 0x11; //0001 0001
            byte headerLast   = (byte) 0x51; //0101 0001
            for(short i = 0; i < payloadCount; i++){
                if(i == 0){
                    header = headerFirst;
                } else if(i == (short) (payloadCount - 1)){
                    header = headerLast;
                } else {
                    header = headerMiddle;
                }
                generateData((short) (toReturnBytes + 2), counterASCII, header, (byte[]) payloads[i], NDEFtypes[i]);
            }
        }
        /*toReturn[2] = (byte) 0xD1; //1101 0001 - header
        toReturn[3] = (byte) 0x01; //Type length is 1
        toReturn[4] = (byte) (payload.length + counterASCII.length); //PayloadLength
        toReturn[5] = NDEFtype; //URL == 0x55, Text == 0x54
        toReturnBytes = (short) (4 + payload.length + counterASCII.length);
        toReturn[0] = (byte) ((toReturnBytes & (short) 0xFF00) >> 8);
        toReturn[1] = (byte) (toReturnBytes & (short) 0x00FF);
        
        Util.arrayCopyNonAtomic(payload, (short) 0, toReturn, (short) 6, (short) payload.length);
        Util.arrayCopyNonAtomic(counterASCII, (short) 0, toReturn, (short) (6 + payload.length), (short) counterASCII.length);*/
        
    }

    /**
     * Process a READ BINARY command
     *
     * This supports simple reads at any offset.
     *
     * The length of the returned data is limited
     * by the maximum R-APDU length as well as by
     * the maximum read size NDEF_MAX_READ.
     *
     * @param apdu to process
     * @throws ISOException on error
     */
    private void processReadBinary(APDU apdu) throws ISOException {
        byte[] buffer = apdu.getBuffer();

        // access the file
        byte[] file = accessFileForRead(selectedFile);
        
        // get and check the read offset
        short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
        if(offset < 0 || offset >= file.length) {
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);
        }
        
        // Update actual data (URL with counter value, URL with HOTP...) only when reading from 0
        if(file == toReturn && offset == 0){
            generateData();
        }
        
        // determine the output size
        short le = apdu.setOutgoingNoChaining();
        if(le > NDEF_MAX_READ) {
            le = NDEF_MAX_READ;
        }

        // adjust for end of file
        short limit = (short)(offset + le);
        if(limit < 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }
        if(limit >= file.length) {
            le = (short)(file.length - offset);
        }

        // send the requested data
        if(selectedFile == FILEID_NDEF_CAPABILITIES) {
            // send fixed capabilities
            
            Util.arrayCopyNonAtomic(file, (short)0,
                                    buffer, (short)0,
                                    (short)file.length);
            
            fixCaps(buffer, (short)0, (short)file.length);
            apdu.setOutgoingLength(le);
            apdu.sendBytesLong(buffer, offset, le);
        } else {
            if(selectedFile == FILEID_NDEF_DATA){
                for(short i = 0; i < file.length; i++){
                    if(file[i] == 0x54 && file[(short)(i-1)] == 0x54){
                        file[i] = 0x55;
                    }
                }
            }
            // send directly
            apdu.setOutgoingLength(le);
            apdu.sendBytesLong(file, offset, le);
        }
    }

    /**
     * Process an UPDATE BINARY command
     *
     * Supports simple writes at any offset.
     *
     * The amount of data that can be written in one
     * operation is limited both by maximum C-APDU
     * length and the maximum write size NDEF_MAX_WRITE.
     *
     * @param apdu to process
     * @throws ISOException on error
     */
    private void processUpdateBinary(APDU apdu) throws ISOException {
        byte[] buffer = apdu.getBuffer();

        // access the file
        byte[] file = accessFileForWrite(selectedFile);

        // get and check the write offset
        short offset = Util.getShort(buffer, ISO7816.OFFSET_P1);
        if(offset < 0 || offset >= file.length) {
            ISOException.throwIt(ISO7816.SW_WRONG_P1P2);
        }

        // get and check the input size
        short lc = (short)(buffer[ISO7816.OFFSET_LC] & 0xFF);
        if(lc > NDEF_MAX_WRITE) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // file limit checks
        short limit = (short)(offset + lc);
        if(limit < 0 || limit >= file.length) {
            ISOException.throwIt(ISO7816.SW_WRONG_LENGTH);
        }

        // perform the update
        Util.arrayCopy(buffer, ISO7816.OFFSET_CDATA, file, offset, lc);
        //generateData();
        
    }

    /**
     * Check if file access should be granted
     *
     * This will perform all necessary checks to determine
     * if an operation can currently be allowed within the
     * policy specified in ACCESS.
     *
     * @param access policy to be checked
     * @return true if access granted, false otherwise
     */
    private boolean checkAccess(byte[] data, byte access) {
        // get protocol and media information
        byte protocol = APDU.getProtocol();
        byte media = (byte)(protocol & APDU.PROTOCOL_MEDIA_MASK);
        // make the decision
        switch(access) {
            case FILE_ACCESS_OPEN:
                return true;
            case FILE_ACCESS_PROP_CONTACT_ONLY:
                return media == APDU.PROTOCOL_MEDIA_DEFAULT;
            case FILE_ACCESS_PROP_WRITE_ONCE:
                return data[0] == 0 && data[1] == 0;
            default:
            case FILE_ACCESS_NONE:
                return false;
        }
    }

    /**
     * Fix up an access policy to reflect current state
     *
     * This is used to squash our custom access policies
     * so that we do not have to present a proprietary
     * policy to unsuspecting host devices.
     *
     * @param data of the file for which to fix
     * @param access policy for to fix
     * @return a fixed access policy
     */
    private byte fixAccess(byte[] data, byte access) {
        // figure out the right policy
        switch(access) {
            // by default we pass through
            default:
                return access;
            // these two require fixing
            case FILE_ACCESS_PROP_CONTACT_ONLY:
            case FILE_ACCESS_PROP_WRITE_ONCE:
                return (checkAccess(data, access))
                        ? FILE_ACCESS_OPEN : FILE_ACCESS_NONE;
        }
    }

    /**
     * Access a file for reading
     *
     * This function serves to perform precondition checks
     * before actually operating on a file in a read operation.
     *
     * If this function succeeds then the given fileId was
     * valid, security access has been granted and reading
     * of data for this file is possible.
     *
     * @param fileId of the file to be read
     * @return data array of the file
     * @throws ISOException on error
     */
    private byte[] accessFileForRead(short fileId) throws ISOException {
        byte[] file = null;
        byte access = FILE_ACCESS_NONE;
        // select relevant data
        if(fileId == FILEID_NDEF_CAPABILITIES) {
            file = ndefCaps;
            access = FILE_ACCESS_OPEN;
        }
        if(fileId == FILEID_NDEF_DATA) {
            if(changedSinceLastParse){

                changedSinceLastParse = false;
                try{
                    parseData(ndefData);
                    messageShown = okData;
                } catch (ISOException e){
                    short errorCode = e.getReason();
                    Util.setShort(shortBuffer, (short) 0, errorCode);
                    UtilBCD.hexToAscii(shortBuffer, (short) 0, (short) 2, errData, (short) 8);
                    messageShown = errData;
                }
                
                
                
            }
            if(messageShown != null){
                file = messageShown;
            } else {
                file = toReturn;
            }
            access = ndefReadAccess;
        }
        // check that we got anything
        if(file == null) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        // perform access checks
        if(!checkAccess(file, access)) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        return file;
    }

    /**
     * Access a file for writing
     *
     * This function serves to perform precondition checks
     * before actually operating on a file in a write operation.
     *
     * If this function succeeds then the given fileId was
     * valid, security access has been granted and writing
     * of data for this file is possible.
     *
     * @param fileId of the file to be written
     * @return data array of the file
     * @throws ISOException on error
     */
    private byte[] accessFileForWrite(short fileId) throws ISOException {
        byte[] file = null;
        byte access = FILE_ACCESS_NONE;
        // CC can not be written
        if(fileId == FILEID_NDEF_CAPABILITIES) {
            ISOException.throwIt(ISO7816.SW_FUNC_NOT_SUPPORTED);
        }
        // select relevant data
        if(fileId == FILEID_NDEF_DATA) {
            changedSinceLastParse = true;
            file = ndefData;
            access = ndefWriteAccess;
        }
        // check that we got something
        if(file == null) {
            ISOException.throwIt(ISO7816.SW_CONDITIONS_NOT_SATISFIED);
        }
        // perform access checks
        if(!checkAccess(file, access)) {
            ISOException.throwIt(ISO7816.SW_SECURITY_STATUS_NOT_SATISFIED);
        }
        return file;
    }

}
