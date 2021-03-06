package org.spongycastle.tls.test;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.spongycastle.asn1.ocsp.BasicOCSPResponse;
import org.spongycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.spongycastle.asn1.ocsp.OCSPResponse;
import org.spongycastle.asn1.ocsp.OCSPResponseStatus;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.ocsp.BasicOCSPResp;
import org.spongycastle.cert.ocsp.CertificateID;
import org.spongycastle.cert.ocsp.OCSPException;
import org.spongycastle.cert.ocsp.OCSPReqBuilder;
import org.spongycastle.cert.ocsp.SingleResp;
import org.spongycastle.cert.ocsp.jcajce.JcaCertificateID;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.DigestCalculator;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.spongycastle.tls.Certificate;
import org.spongycastle.tls.crypto.TlsCertificate;
import org.spongycastle.tls.crypto.impl.jcajce.JcaTlsCrypto;
import org.spongycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;

import junit.framework.TestCase;

public class OCSPTest
    extends TestCase
{
    interface OCSPResponder
    {
        OCSPResponse[] getResponses(Certificate certs)
            throws IOException;
    }

    private class TestOCSPResponderImpl
        implements OCSPResponder
    {
        private final TestOCSPCertServer server;
        private final DigestCalculator digCalc;
        private final X509Certificate caCert;

        public TestOCSPResponderImpl(TestOCSPCertServer server)
            throws OperatorCreationException
        {
            this.server = server;
            this.caCert = server.getCACert();
            this.digCalc = new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1);
        }

        public OCSPResponse[] getResponses(Certificate certs)
            throws IOException
        {
            TlsCertificate[] certList = certs.getCertificateList();
            List<OCSPResponse> responses = new ArrayList<OCSPResponse>();

            for (int i = 0; i != certList.length; i++)
            {
                try
                {
                    OCSPReqBuilder reqBuilder = new OCSPReqBuilder();
                    
                    reqBuilder.addRequest(new CertificateID(digCalc, new X509CertificateHolder(caCert.getEncoded()), certList[i].getSerialNumber()));

                    responses.add(server.respond(reqBuilder.build()).toASN1Structure());
                }
                catch (OCSPException e)
                {
                    throw new IOException("OCSP issue: " + e.getMessage());
                }
                catch (CertificateEncodingException e)
                {
                    throw new IOException("CA encoding issue: " + e.getMessage());
                }
                catch (Exception e)
                {
                    throw new IOException("OCSP response issue: " + e.getMessage());
                }
            }

            return responses.toArray(new OCSPResponse[responses.size()]);
        }
    }

    public void setUp()
    {
        if (Security.getProvider("SC") == null)
        {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
    
    public void testOCSPResponder()
        throws Exception
    {
        JcaTlsCrypto crypto = (JcaTlsCrypto)new JcaTlsCryptoProvider().create(new SecureRandom());

        DigestCalculator digCalc = new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1);
        TestOCSPCertServer server = new TestOCSPCertServer();
        X509Certificate caCert = server.getCACert();

        X509CertificateHolder cert1 = server.issueClientCert("CN=Okay", false).getCertificate();

        X509CertificateHolder cert2 = server.issueClientCert("CN=Revoked", true).getCertificate();

        OCSPResponder responder = new TestOCSPResponderImpl(server);

        Certificate certs = new Certificate(new TlsCertificate[] {
            crypto.createCertificate(cert1.getEncoded()),
            crypto.createCertificate(cert2.getEncoded())});

        OCSPResponse[] responses = responder.getResponses(certs);

        assertEquals(2, responses.length);

        OCSPResponse response = responses[0];

        assertEquals(OCSPResponseStatus.SUCCESSFUL, response.getResponseStatus().getValue().intValue());
        assertEquals(OCSPObjectIdentifiers.id_pkix_ocsp_basic, response.getResponseBytes().getResponseType());
        
        BasicOCSPResp basicResp = new BasicOCSPResp(BasicOCSPResponse.getInstance(response.getResponseBytes().getResponse().getOctets()));

        SingleResp[] resps = basicResp.getResponses();

        assertEquals(1, resps.length);

        assertEquals(resps[0].getCertID(), new JcaCertificateID(digCalc, caCert, cert1.getSerialNumber()));
        assertNull(resps[0].getCertStatus());        // OKAY

        response = responses[1];

        assertEquals(OCSPResponseStatus.SUCCESSFUL, response.getResponseStatus().getValue().intValue());
        assertEquals(OCSPObjectIdentifiers.id_pkix_ocsp_basic, response.getResponseBytes().getResponseType());

        basicResp = new BasicOCSPResp(BasicOCSPResponse.getInstance(response.getResponseBytes().getResponse().getOctets()));

        resps = basicResp.getResponses();

        assertEquals(1, resps.length);

        assertEquals(resps[0].getCertID(), new JcaCertificateID(digCalc, caCert, cert2.getSerialNumber()));
        assertNotNull(resps[0].getCertStatus());
    }
}
