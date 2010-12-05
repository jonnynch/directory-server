/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.directory.server.kerberos.kdc.ticketgrant;


import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.security.auth.kerberos.KerberosPrincipal;

import org.apache.directory.server.i18n.I18n;
import org.apache.directory.server.kerberos.kdc.KdcContext;
import org.apache.directory.server.kerberos.kdc.KdcServer;
import org.apache.directory.server.kerberos.shared.KerberosConstants;
import org.apache.directory.server.kerberos.shared.crypto.checksum.ChecksumHandler;
import org.apache.directory.server.kerberos.shared.crypto.encryption.CipherTextHandler;
import org.apache.directory.server.kerberos.shared.crypto.encryption.KeyUsage;
import org.apache.directory.server.kerberos.shared.crypto.encryption.RandomKeyFactory;
import org.apache.directory.server.kerberos.shared.exceptions.KerberosException;
import org.apache.directory.server.kerberos.shared.io.decoder.ApplicationRequestDecoder;
import org.apache.directory.server.kerberos.shared.messages.ApplicationRequest;
import org.apache.directory.server.kerberos.shared.messages.components.EncTicketPartModifier;
import org.apache.directory.server.kerberos.shared.replay.ReplayCache;
import org.apache.directory.server.kerberos.shared.store.PrincipalStore;
import org.apache.directory.server.kerberos.shared.store.PrincipalStoreEntry;
import org.apache.directory.shared.kerberos.KerberosTime;
import org.apache.directory.shared.kerberos.KerberosUtils;
import org.apache.directory.shared.kerberos.codec.options.KdcOptions;
import org.apache.directory.shared.kerberos.codec.types.EncryptionType;
import org.apache.directory.shared.kerberos.codec.types.PaDataType;
import org.apache.directory.shared.kerberos.components.AuthorizationData;
import org.apache.directory.shared.kerberos.components.Checksum;
import org.apache.directory.shared.kerberos.components.EncKdcRepPart;
import org.apache.directory.shared.kerberos.components.EncTicketPart;
import org.apache.directory.shared.kerberos.components.EncryptedData;
import org.apache.directory.shared.kerberos.components.EncryptionKey;
import org.apache.directory.shared.kerberos.components.HostAddress;
import org.apache.directory.shared.kerberos.components.HostAddresses;
import org.apache.directory.shared.kerberos.components.KdcReq;
import org.apache.directory.shared.kerberos.components.LastReq;
import org.apache.directory.shared.kerberos.components.PaData;
import org.apache.directory.shared.kerberos.components.PrincipalName;
import org.apache.directory.shared.kerberos.crypto.checksum.ChecksumType;
import org.apache.directory.shared.kerberos.exceptions.ErrorType;
import org.apache.directory.shared.kerberos.flags.TicketFlag;
import org.apache.directory.shared.kerberos.messages.Authenticator;
import org.apache.directory.shared.kerberos.messages.EncTgsRepPart;
import org.apache.directory.shared.kerberos.messages.TgsRep;
import org.apache.directory.shared.kerberos.messages.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * @author <a href="mailto:dev@directory.apache.org">Apache Directory Project</a>
 */
public class TicketGrantingService
{
    
    /** the log for this class */
    private static final Logger LOG = LoggerFactory.getLogger( TicketGrantingService.class );
    
    private static final CipherTextHandler cipherTextHandler = new CipherTextHandler();

    private static final String SERVICE_NAME = "Ticket-Granting Service (TGS)";

    private static final ChecksumHandler checksumHandler = new ChecksumHandler();

    public static void execute( TicketGrantingContext tgsContext ) throws Exception
    {
        if ( LOG.isDebugEnabled() )
        {
            monitorRequest( tgsContext );
        }

        configureTicketGranting( tgsContext);
        selectEncryptionType( tgsContext );
        getAuthHeader( tgsContext );
        verifyTgt( tgsContext );
        getTicketPrincipalEntry( tgsContext );
        verifyTgtAuthHeader( tgsContext );
        verifyBodyChecksum( tgsContext );
        getRequestPrincipalEntry( tgsContext );
        generateTicket( tgsContext );
        buildReply( tgsContext );
    }
    
    
    private static void configureTicketGranting( TicketGrantingContext tgsContext ) throws KerberosException
    {
        tgsContext.setCipherTextHandler( cipherTextHandler );

        if ( tgsContext.getRequest().getProtocolVersionNumber() != KerberosConstants.KERBEROS_V5 )
        {
            throw new KerberosException( ErrorType.KDC_ERR_BAD_PVNO );
        }
    }
    

    private static void monitorRequest( KdcContext kdcContext ) throws Exception
    {
        KdcReq request = kdcContext.getRequest();

        try
        {
            String clientAddress = kdcContext.getClientAddress().getHostAddress();

            StringBuffer sb = new StringBuffer();

            sb.append( "Received " + SERVICE_NAME + " request:" );
            sb.append( "\n\t" + "messageType:           " + request.getMessageType() );
            sb.append( "\n\t" + "protocolVersionNumber: " + request.getProtocolVersionNumber() );
            sb.append( "\n\t" + "clientAddress:         " + clientAddress );
            sb.append( "\n\t" + "nonce:                 " + request.getKdcReqBody().getNonce() );
            sb.append( "\n\t" + "kdcOptions:            " + request.getKdcReqBody().getKdcOptions() );
            sb.append( "\n\t" + "clientPrincipal:       " + request.getKdcReqBody().getCName() );
            sb.append( "\n\t" + "serverPrincipal:       " + request.getKdcReqBody().getSName() );
            sb.append( "\n\t" + "encryptionType:        " + KerberosUtils.getEncryptionTypesString( request.getKdcReqBody().getEType() ) );
            sb.append( "\n\t" + "realm:                 " + request.getKdcReqBody().getRealm() );
            sb.append( "\n\t" + "from time:             " + request.getKdcReqBody().getFrom() );
            sb.append( "\n\t" + "till time:             " + request.getKdcReqBody().getTill() );
            sb.append( "\n\t" + "renew-till time:       " + request.getKdcReqBody().getRTime() );
            sb.append( "\n\t" + "hostAddresses:         " + request.getKdcReqBody().getAddresses() );

            LOG.debug( sb.toString() );
        }
        catch ( Exception e )
        {
            // This is a monitor.  No exceptions should bubble up.
            LOG.error( I18n.err( I18n.ERR_153 ), e );
        }
    }
    
    
    private static void selectEncryptionType( TicketGrantingContext tgsContext ) throws Exception
    {
        KdcContext kdcContext = (KdcContext)tgsContext;
        KdcServer config = kdcContext.getConfig();

        Set<EncryptionType> requestedTypes = kdcContext.getRequest().getKdcReqBody().getEType();

        EncryptionType bestType = KerberosUtils.getBestEncryptionType( requestedTypes, config.getEncryptionTypes() );

        LOG.debug( "Session will use encryption type {}.", bestType );

        if ( bestType == null )
        {
            throw new KerberosException( ErrorType.KDC_ERR_ETYPE_NOSUPP );
        }

        kdcContext.setEncryptionType( bestType );
    }
    
    
    private static void getAuthHeader( TicketGrantingContext tgsContext ) throws Exception
    {
        KdcReq request = tgsContext.getRequest();

        if ( ( request.getPaData() == null ) || ( request.getPaData().size() < 1 ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_PADATA_TYPE_NOSUPP );
        }

        byte[] undecodedAuthHeader = null;

        for ( PaData paData : request.getPaData() )
        {
            if ( paData.getPaDataType() == PaDataType.PA_TGS_REQ )
            {
                undecodedAuthHeader = paData.getPaDataValue();
            }
        }

        if ( undecodedAuthHeader == null )
        {
            throw new KerberosException( ErrorType.KDC_ERR_PADATA_TYPE_NOSUPP );
        }

        ApplicationRequestDecoder decoder = new ApplicationRequestDecoder();
        ApplicationRequest authHeader = decoder.decode( undecodedAuthHeader );
        
        Ticket tgt = authHeader.getTicket();

        tgsContext.setAuthHeader( authHeader );
        tgsContext.setTgt( tgt );
    }
    
    
    public static void verifyTgt( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KdcServer config = tgsContext.getConfig();
        Ticket tgt = tgsContext.getTgt();

        // Check primary realm.
        if ( !tgt.getRealm().equals( config.getPrimaryRealm() ) )
        {
            throw new KerberosException( ErrorType.KRB_AP_ERR_NOT_US );
        }

        String tgtServerName = tgt.getServerPrincipal().getName();
        String requestServerName = tgsContext.getRequest().getKdcReqBody().getSName().getNameString();

        /*
         * if (tgt.sname is not a TGT for local realm and is not req.sname)
         *     then error_out(KRB_AP_ERR_NOT_US);
         */
        if ( !tgtServerName.equals( config.getServicePrincipal().getName() )
            && !tgtServerName.equals( requestServerName ) )
        {
            throw new KerberosException( ErrorType.KRB_AP_ERR_NOT_US );
        }
    }
    
    
    private static void getTicketPrincipalEntry( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KerberosPrincipal principal = tgsContext.getTgt().getServerPrincipal();
        PrincipalStore store = tgsContext.getStore();

        PrincipalStoreEntry entry = KerberosUtils.getEntry( principal, store, ErrorType.KDC_ERR_S_PRINCIPAL_UNKNOWN );
        tgsContext.setTicketPrincipalEntry( entry );
    }


    private static void verifyTgtAuthHeader( TicketGrantingContext tgsContext ) throws KerberosException
    {
        ApplicationRequest authHeader = tgsContext.getAuthHeader();
        Ticket tgt = tgsContext.getTgt();
        
        boolean isValidate = tgsContext.getRequest().getKdcReqBody().getKdcOptions().get( KdcOptions.VALIDATE );

        EncryptionType encryptionType = tgt.getEncPart().getEType();
        EncryptionKey serverKey = tgsContext.getTicketPrincipalEntry().getKeyMap().get( encryptionType );

        long clockSkew = tgsContext.getConfig().getAllowableClockSkew();
        ReplayCache replayCache = tgsContext.getConfig().getReplayCache();
        boolean emptyAddressesAllowed = tgsContext.getConfig().isEmptyAddressesAllowed();
        InetAddress clientAddress = tgsContext.getClientAddress();
        CipherTextHandler cipherTextHandler = tgsContext.getCipherTextHandler();

        Authenticator authenticator = KerberosUtils.verifyAuthHeader( authHeader, tgt, serverKey, clockSkew, replayCache,
            emptyAddressesAllowed, clientAddress, cipherTextHandler, KeyUsage.NUMBER7, isValidate );

        tgsContext.setAuthenticator( authenticator );
    }
    
    
    private static void verifyBodyChecksum( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KdcServer config = tgsContext.getConfig();

        if ( config.isBodyChecksumVerified() )
        {
            byte[] bodyBytes = tgsContext.getRequest().getBodyBytes();
            Checksum authenticatorChecksum = tgsContext.getAuthenticator().getCksum();

            if ( authenticatorChecksum == null || authenticatorChecksum.getChecksumType() == null
                || authenticatorChecksum.getChecksumValue() == null || bodyBytes == null )
            {
                throw new KerberosException( ErrorType.KRB_AP_ERR_INAPP_CKSUM );
            }

            LOG.debug( "Verifying body checksum type '{}'.", authenticatorChecksum.getChecksumType() );

            checksumHandler.verifyChecksum( authenticatorChecksum, bodyBytes, null, KeyUsage.NUMBER8 );
        }
    }
    

    public static void getRequestPrincipalEntry( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KerberosPrincipal principal = KerberosUtils.getKerberosPrincipal( 
            tgsContext.getRequest().getKdcReqBody().getSName(), tgsContext.getRequest().getKdcReqBody().getRealm() );
        PrincipalStore store = tgsContext.getStore();

        PrincipalStoreEntry entry = KerberosUtils.getEntry( principal, store, ErrorType.KDC_ERR_S_PRINCIPAL_UNKNOWN );
        tgsContext.setRequestPrincipalEntry( entry );
    }

    
    private static void generateTicket( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KdcReq request = tgsContext.getRequest();
        Ticket tgt = tgsContext.getTgt();
        Authenticator authenticator = tgsContext.getAuthenticator();
        CipherTextHandler cipherTextHandler = tgsContext.getCipherTextHandler();
        KerberosPrincipal ticketPrincipal = KerberosUtils.getKerberosPrincipal( 
            request.getKdcReqBody().getSName(), request.getKdcReqBody().getRealm() );

        EncryptionType encryptionType = tgsContext.getEncryptionType();
        EncryptionKey serverKey = tgsContext.getRequestPrincipalEntry().getKeyMap().get( encryptionType );

        KdcServer config = tgsContext.getConfig();

        EncTicketPartModifier newTicketBody = new EncTicketPartModifier();

        newTicketBody.setClientAddresses( tgt.getEncTicketPart().getClientAddresses() );

        processFlags( config, request, tgt, newTicketBody );

        EncryptionKey sessionKey = RandomKeyFactory.getRandomKey( tgsContext.getEncryptionType() );
        newTicketBody.setSessionKey( sessionKey );

        newTicketBody.setClientPrincipal( tgt.getEncTicketPart().getCName() );

        if ( request.getKdcReqBody().getEncAuthorizationData() != null )
        {
            AuthorizationData authData = ( AuthorizationData ) cipherTextHandler.unseal( AuthorizationData.class,
                authenticator.getSubKey(), request.getKdcReqBody().getEncAuthorizationData(), KeyUsage.NUMBER4 );
            authData.addEntry( tgt.getEncTicketPart().getAuthorizationData() );
            newTicketBody.setAuthorizationData( authData );
        }

        processTransited( newTicketBody, tgt );

        processTimes( config, request, newTicketBody, tgt );

        EncTicketPart ticketPart = newTicketBody.getEncTicketPart();

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.ENC_TKT_IN_SKEY ) )
        {
            /*
             * if (server not specified) then
             *         server = req.second_ticket.client;
             * endif
             * 
             * if ((req.second_ticket is not a TGT) or
             *     (req.second_ticket.client != server)) then
             *         error_out(KDC_ERR_POLICY);
             * endif
             * 
             * new_tkt.enc-part := encrypt OCTET STRING using etype_for_key(second-ticket.key), second-ticket.key;
             */
            throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
        }
        else
        {
            EncryptedData encryptedData = cipherTextHandler.seal( serverKey, ticketPart, KeyUsage.NUMBER2 );

            Ticket newTicket = new Ticket( ticketPrincipal, encryptedData );
            newTicket.setEncTicketPart( ticketPart );

            tgsContext.setNewTicket( newTicket );
        }
    }
    

    private static void buildReply( TicketGrantingContext tgsContext ) throws KerberosException
    {
        KdcReq request = tgsContext.getRequest();
        Ticket tgt = tgsContext.getTgt();
        Ticket newTicket = tgsContext.getNewTicket();

        TgsRep reply = new TgsRep();
        
        reply.setCName( tgt.getEncTicketPart().getCName() );
        reply.setTicket( newTicket );
        
        EncKdcRepPart encKdcRepPart = new EncKdcRepPart();
        
        encKdcRepPart.setKey( newTicket.getEncTicketPart().getKey() );
        encKdcRepPart.setNonce( request.getKdcReqBody().getNonce() );
        // TODO - resp.last-req := fetch_last_request_info(client); requires store
        encKdcRepPart.setLastReq( new LastReq() );
        encKdcRepPart.setFlags( newTicket.getEncTicketPart().getFlags() );
        encKdcRepPart.setClientAddresses( newTicket.getEncTicketPart().getClientAddresses() );
        encKdcRepPart.setAuthTime( newTicket.getEncTicketPart().getAuthTime() );
        encKdcRepPart.setStartTime( newTicket.getEncTicketPart().getStartTime() );
        encKdcRepPart.setEndTime( newTicket.getEncTicketPart().getEndTime() );
        encKdcRepPart.setSName( newTicket.getSName() );

        if ( newTicket.getEncTicketPart().getFlags().isRenewable() )
        {
            encKdcRepPart.setRenewTill( newTicket.getEncTicketPart().getRenewTill() );
        }

        if ( LOG.isDebugEnabled() )
        {
            monitorContext( tgsContext );
            monitorReply( reply, encKdcRepPart );
        }

        EncTgsRepPart encTgsRepPart = new EncTgsRepPart();
        encTgsRepPart.setEncKdcRepPart( encKdcRepPart );
        
        Authenticator authenticator = tgsContext.getAuthenticator();
        
        EncryptedData encryptedData;
        
        if ( authenticator.getSubKey() != null )
        {
            encryptedData = cipherTextHandler.seal( authenticator.getSubKey(), encTgsRepPart, KeyUsage.NUMBER9 );
        }
        else
        {
            encryptedData = cipherTextHandler.seal( tgt.getEncTicketPart().getKey(), encTgsRepPart, KeyUsage.NUMBER8 );
        }
        
        reply.setEncPart( encryptedData );

        tgsContext.setReply( reply );
    }
    
    
    private static void monitorContext( TicketGrantingContext tgsContext )
    {
        try
        {
            Ticket tgt = tgsContext.getTgt();
            long clockSkew = tgsContext.getConfig().getAllowableClockSkew();
            ChecksumType checksumType = tgsContext.getAuthenticator().getCksum().getChecksumType();
            InetAddress clientAddress = tgsContext.getClientAddress();
            HostAddresses clientAddresses = tgt.getEncTicketPart().getClientAddresses();

            boolean caddrContainsSender = false;
            if ( tgt.getEncTicketPart().getClientAddresses() != null )
            {
                caddrContainsSender = tgt.getEncTicketPart().getClientAddresses().contains( new HostAddress( clientAddress ) );
            }

            StringBuffer sb = new StringBuffer();

            sb.append( "Monitoring " + SERVICE_NAME + " context:" );

            sb.append( "\n\t" + "clockSkew              " + clockSkew );
            sb.append( "\n\t" + "checksumType           " + checksumType );
            sb.append( "\n\t" + "clientAddress          " + clientAddress );
            sb.append( "\n\t" + "clientAddresses        " + clientAddresses );
            sb.append( "\n\t" + "caddr contains sender  " + caddrContainsSender );

            PrincipalName requestServerPrincipal = tgsContext.getRequest().getKdcReqBody().getSName();
            PrincipalStoreEntry requestPrincipal = tgsContext.getRequestPrincipalEntry();

            sb.append( "\n\t" + "principal              " + requestServerPrincipal );
            sb.append( "\n\t" + "cn                     " + requestPrincipal.getCommonName() );
            sb.append( "\n\t" + "realm                  " + requestPrincipal.getRealmName() );
            sb.append( "\n\t" + "principal              " + requestPrincipal.getPrincipal() );
            sb.append( "\n\t" + "SAM type               " + requestPrincipal.getSamType() );

            PrincipalName ticketServerPrincipal = tgsContext.getTgt().getSName();
            PrincipalStoreEntry ticketPrincipal = tgsContext.getTicketPrincipalEntry();

            sb.append( "\n\t" + "principal              " + ticketServerPrincipal );
            sb.append( "\n\t" + "cn                     " + ticketPrincipal.getCommonName() );
            sb.append( "\n\t" + "realm                  " + ticketPrincipal.getRealmName() );
            sb.append( "\n\t" + "principal              " + ticketPrincipal.getPrincipal() );
            sb.append( "\n\t" + "SAM type               " + ticketPrincipal.getSamType() );

            EncryptionType encryptionType = tgsContext.getTgt().getEncPart().getEType();
            int keyVersion = ticketPrincipal.getKeyMap().get( encryptionType ).getKeyVersion();
            sb.append( "\n\t" + "Ticket key type        " + encryptionType );
            sb.append( "\n\t" + "Service key version    " + keyVersion );

            LOG.debug( sb.toString() );
        }
        catch ( Exception e )
        {
            // This is a monitor.  No exceptions should bubble up.
            LOG.error( I18n.err( I18n.ERR_154 ), e );
        }
    }

    
    private static void monitorReply( TgsRep success, EncKdcRepPart part )
    {
        try
        {
            StringBuffer sb = new StringBuffer();
            
            sb.append( "Responding with " + SERVICE_NAME + " reply:" );
            sb.append( "\n\t" + "messageType:           " + success.getMessageType() );
            sb.append( "\n\t" + "protocolVersionNumber: " + success.getProtocolVersionNumber() );
            sb.append( "\n\t" + "nonce:                 " + part.getNonce() );
            sb.append( "\n\t" + "clientPrincipal:       " + success.getCName() );
            sb.append( "\n\t" + "client realm:          " + success.getCRealm() );
            sb.append( "\n\t" + "serverPrincipal:       " + part.getSName() );
            sb.append( "\n\t" + "server realm:          " + part.getSRealm() );
            sb.append( "\n\t" + "auth time:             " + part.getAuthTime() );
            sb.append( "\n\t" + "start time:            " + part.getStartTime() );
            sb.append( "\n\t" + "end time:              " + part.getEndTime() );
            sb.append( "\n\t" + "renew-till time:       " + part.getRenewTill() );
            sb.append( "\n\t" + "hostAddresses:         " + part.getClientAddresses() );
            
            LOG.debug( sb.toString() );
        }
        catch ( Exception e )
        {
            // This is a monitor.  No exceptions should bubble up.
            LOG.error( I18n.err( I18n.ERR_155 ), e );
        }
    }
    

    
    private static void processFlags( KdcServer config, KdcReq request, Ticket tgt,
        EncTicketPartModifier newTicketBody ) throws KerberosException
    {
        if ( tgt.getEncTicketPart().getFlags().isPreAuth() )
        {
            newTicketBody.setFlag( TicketFlag.PRE_AUTHENT );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.FORWARDABLE ) )
        {
            if ( !config.isForwardableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isForwardable() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            newTicketBody.setFlag( TicketFlag.FORWARDABLE );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.FORWARDED ) )
        {
            if ( !config.isForwardableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isForwardable() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            if ( request.getKdcReqBody().getAddresses() != null && request.getKdcReqBody().getAddresses().getAddresses() != null
                && request.getKdcReqBody().getAddresses().getAddresses().length > 0 )
            {
                newTicketBody.setClientAddresses( request.getKdcReqBody().getAddresses() );
            }
            else
            {
                if ( !config.isEmptyAddressesAllowed() )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_POLICY );
                }
            }

            newTicketBody.setFlag( TicketFlag.FORWARDED );
        }

        if ( tgt.getEncTicketPart().getFlags().isForwarded() )
        {
            newTicketBody.setFlag( TicketFlag.FORWARDED );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.PROXIABLE ) )
        {
            if ( !config.isProxiableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isProxiable() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            newTicketBody.setFlag( TicketFlag.PROXIABLE );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.PROXY ) )
        {
            if ( !config.isProxiableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isProxiable() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            if ( request.getKdcReqBody().getAddresses() != null && request.getKdcReqBody().getAddresses().getAddresses() != null
                && request.getKdcReqBody().getAddresses().getAddresses().length > 0 )
            {
                newTicketBody.setClientAddresses( request.getKdcReqBody().getAddresses() );
            }
            else
            {
                if ( !config.isEmptyAddressesAllowed() )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_POLICY );
                }
            }

            newTicketBody.setFlag( TicketFlag.PROXY );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.ALLOW_POSTDATE ) )
        {
            if ( !config.isPostdatedAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isMayPosdate() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            newTicketBody.setFlag( TicketFlag.MAY_POSTDATE );
        }

        /*
         * "Otherwise, if the TGT has the MAY-POSTDATE flag set, then the resulting
         * ticket will be postdated, and the requested starttime is checked against
         * the policy of the local realm.  If acceptable, the ticket's starttime is
         * set as requested, and the INVALID flag is set.  The postdated ticket MUST
         * be validated before use by presenting it to the KDC after the starttime
         * has been reached.  However, in no case may the starttime, endtime, or
         * renew-till time of a newly-issued postdated ticket extend beyond the
         * renew-till time of the TGT."
         */
        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) )
        {
            if ( !config.isPostdatedAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isMayPosdate() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            newTicketBody.setFlag( TicketFlag.POSTDATED );
            newTicketBody.setFlag( TicketFlag.INVALID );

            newTicketBody.setStartTime( request.getKdcReqBody().getFrom() );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.VALIDATE ) )
        {
            if ( !config.isPostdatedAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isInvalid() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            KerberosTime startTime = ( tgt.getEncTicketPart().getStartTime() != null ) ? 
                    tgt.getEncTicketPart().getStartTime() : 
                        tgt.getEncTicketPart().getAuthTime();

            if ( startTime.greaterThan( new KerberosTime() ) )
            {
                throw new KerberosException( ErrorType.KRB_AP_ERR_TKT_NYV );
            }

            echoTicket( newTicketBody, tgt );
            newTicketBody.clearFlag( TicketFlag.INVALID );
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_0 ) || 
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_7 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_9 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_10 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_11 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_12 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_13 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_14 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_15 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_16 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_17 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_18 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_19 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_20 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_21 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_22 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_23 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_24 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_25 ) ||
             request.getKdcReqBody().getKdcOptions().get( KdcOptions.RESERVED_29 ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
        }
    }


    private static void processTimes( KdcServer config, KdcReq request, EncTicketPartModifier newTicketBody,
        Ticket tgt ) throws KerberosException
    {
        KerberosTime now = new KerberosTime();

        newTicketBody.setAuthTime( tgt.getEncTicketPart().getAuthTime() );

        KerberosTime startTime = request.getKdcReqBody().getFrom();

        /*
         * "If the requested starttime is absent, indicates a time in the past,
         * or is within the window of acceptable clock skew for the KDC and the
         * POSTDATE option has not been specified, then the starttime of the
         * ticket is set to the authentication server's current time."
         */
        if ( startTime == null || startTime.lessThan( now ) || startTime.isInClockSkew( config.getAllowableClockSkew() )
            && !request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) )
        {
            startTime = now;
        }

        /*
         * "If it indicates a time in the future beyond the acceptable clock skew,
         * but the POSTDATED option has not been specified or the MAY-POSTDATE flag
         * is not set in the TGT, then the error KDC_ERR_CANNOT_POSTDATE is
         * returned."
         */
        if ( startTime != null && startTime.greaterThan( now )
            && !startTime.isInClockSkew( config.getAllowableClockSkew() )
            && ( !request.getKdcReqBody().getKdcOptions().get( KdcOptions.POSTDATED ) || !tgt.getEncTicketPart().getFlags().isMayPosdate() ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_CANNOT_POSTDATE );
        }

        KerberosTime renewalTime = null;
        KerberosTime kerberosEndTime = null;

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEW ) )
        {
            if ( !config.isRenewableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            if ( !tgt.getEncTicketPart().getFlags().isRenewable() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_BADOPTION );
            }

            if ( tgt.getEncTicketPart().getRenewTill().lessThan( now ) )
            {
                throw new KerberosException( ErrorType.KRB_AP_ERR_TKT_EXPIRED );
            }

            echoTicket( newTicketBody, tgt );

            newTicketBody.setStartTime( now );

            KerberosTime tgtStartTime = ( tgt.getEncTicketPart().getStartTime() != null ) ? 
                tgt.getEncTicketPart().getStartTime() : 
                    tgt.getEncTicketPart().getAuthTime();

            long oldLife = tgt.getEncTicketPart().getEndTime().getTime() - tgtStartTime.getTime();

            kerberosEndTime = new KerberosTime( Math.min( tgt.getEncTicketPart().getRenewTill().getTime(), now.getTime() + oldLife ) );
            newTicketBody.setEndTime( kerberosEndTime );
        }
        else
        {
            if ( newTicketBody.getEncTicketPart().getStartTime() == null )
            {
                newTicketBody.setStartTime( now );
            }

            KerberosTime till;
            if ( request.getKdcReqBody().getTill().isZero() )
            {
                till = KerberosTime.INFINITY;
            }
            else
            {
                till = request.getKdcReqBody().getTill();
            }

            /*
             * The end time is the minimum of (a) the requested till time or (b)
             * the start time plus maximum lifetime as configured in policy or (c)
             * the end time of the TGT.
             */
            List<KerberosTime> minimizer = new ArrayList<KerberosTime>();
            minimizer.add( till );
            minimizer.add( new KerberosTime( startTime.getTime() + config.getMaximumTicketLifetime() ) );
            minimizer.add( tgt.getEncTicketPart().getEndTime() );
            kerberosEndTime = Collections.min( minimizer );

            newTicketBody.setEndTime( kerberosEndTime );

            if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEWABLE_OK ) && kerberosEndTime.lessThan( request.getKdcReqBody().getTill() )
                && tgt.getEncTicketPart().getFlags().isRenewable() )
            {
                if ( !config.isRenewableAllowed() )
                {
                    throw new KerberosException( ErrorType.KDC_ERR_POLICY );
                }

                // We set the RENEWABLE option for later processing.                           
                request.getKdcReqBody().getKdcOptions().set( KdcOptions.RENEWABLE );
                long rtime = Math.min( request.getKdcReqBody().getTill().getTime(), tgt.getEncTicketPart().getRenewTill().getTime() );
                renewalTime = new KerberosTime( rtime );
            }
        }

        if ( renewalTime == null )
        {
            renewalTime = request.getKdcReqBody().getRTime();
        }

        KerberosTime rtime;
        if ( renewalTime != null && renewalTime.isZero() )
        {
            rtime = KerberosTime.INFINITY;
        }
        else
        {
            rtime = renewalTime;
        }

        if ( request.getKdcReqBody().getKdcOptions().get( KdcOptions.RENEWABLE ) && tgt.getEncTicketPart().getFlags().isRenewable() )
        {
            if ( !config.isRenewableAllowed() )
            {
                throw new KerberosException( ErrorType.KDC_ERR_POLICY );
            }

            newTicketBody.setFlag( TicketFlag.RENEWABLE );

            /*
             * The renew-till time is the minimum of (a) the requested renew-till
             * time or (b) the start time plus maximum renewable lifetime as
             * configured in policy or (c) the renew-till time of the TGT.
             */
            List<KerberosTime> minimizer = new ArrayList<KerberosTime>();

            /*
             * 'rtime' KerberosTime is OPTIONAL
             */
            if ( rtime != null )
            {
                minimizer.add( rtime );
            }

            minimizer.add( new KerberosTime( startTime.getTime() + config.getMaximumRenewableLifetime() ) );
            minimizer.add( tgt.getEncTicketPart().getRenewTill() );
            newTicketBody.setRenewTill( Collections.min( minimizer ) );
        }

        /*
         * "If the requested expiration time minus the starttime (as determined
         * above) is less than a site-determined minimum lifetime, an error
         * message with code KDC_ERR_NEVER_VALID is returned."
         */
        if ( kerberosEndTime.lessThan( startTime ) )
        {
            throw new KerberosException( ErrorType.KDC_ERR_NEVER_VALID );
        }

        long ticketLifeTime = Math.abs( startTime.getTime() - kerberosEndTime.getTime() );
        if ( ticketLifeTime < config.getAllowableClockSkew() )
        {
            throw new KerberosException( ErrorType.KDC_ERR_NEVER_VALID );
        }
    }


    /*
     * if (realm_tgt_is_for(tgt) := tgt.realm) then
     *         // tgt issued by local realm
     *         new_tkt.transited := tgt.transited;
     * else
     *         // was issued for this realm by some other realm
     *         if (tgt.transited.tr-type not supported) then
     *                 error_out(KDC_ERR_TRTYPE_NOSUPP);
     *         endif
     * 
     *         new_tkt.transited := compress_transited(tgt.transited + tgt.realm)
     * endif
     */    
    private static void processTransited( EncTicketPartModifier newTicketBody, Ticket tgt )
    {
        // TODO - currently no transited support other than local
        newTicketBody.setTransitedEncoding( tgt.getEncTicketPart().getTransitedEncoding() );
    }

    
    private static void echoTicket( EncTicketPartModifier newTicketBody, Ticket tgt )
    {
        EncTicketPart encTicketpart = tgt.getEncTicketPart();
        newTicketBody.setAuthorizationData( encTicketpart.getAuthorizationData() );
        newTicketBody.setAuthTime( encTicketpart.getAuthTime() );
        newTicketBody.setClientAddresses( encTicketpart.getClientAddresses() );
        newTicketBody.setClientPrincipal( encTicketpart.getCName() );
        newTicketBody.setEndTime( encTicketpart.getEndTime() );
        newTicketBody.setFlags( encTicketpart.getFlags() );
        newTicketBody.setRenewTill( encTicketpart.getRenewTill() );
        newTicketBody.setSessionKey( encTicketpart.getKey() );
        newTicketBody.setTransitedEncoding( encTicketpart.getTransitedEncoding() );
    }
}
