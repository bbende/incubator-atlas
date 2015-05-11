/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.metadata.hive.hook;

import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.metadata.PropertiesUtil;
import org.apache.hadoop.metadata.security.SecurityProperties;
import org.apache.hadoop.security.alias.JavaKeyStoreProvider;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.security.ssl.SSLHostnameVerifier;
import org.mortbay.jetty.webapp.WebAppContext;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileWriter;
import java.net.URL;
import java.nio.file.Files;

import static org.apache.hadoop.metadata.security.SecurityProperties.*;

/**
 * Perform all the necessary setup steps for client and server comm over SSL/Kerberos, but then don't estalish a
 * kerberos user for the invocation.  Need a separate use case since the Jersey layer cached the URL connection handler,
 * which indirectly caches the kerberos delegation token.
 */
public class NegativeSSLAndKerberosHiveHookIT extends BaseSSLAndKerberosTest {

    private Driver driver;
    private SessionState ss;
    private TestSecureEmbeddedServer secureEmbeddedServer;
    private String originalConf;

    @BeforeClass
    public void setUp() throws Exception {
        //Set-up hive session
        HiveConf conf = getHiveConf();
        driver = new Driver(conf);
        ss = new SessionState(conf, System.getProperty("user.name"));
        ss = SessionState.start(ss);
        SessionState.setCurrentSessionState(ss);

        jksPath = new Path(Files.createTempDirectory("tempproviders").toString(), "test.jks");
        providerUrl = JavaKeyStoreProvider.SCHEME_NAME + "://file" + jksPath.toUri();

        String persistDir = null;
        URL resource = NegativeSSLAndKerberosHiveHookIT.class.getResource("/");
        if (resource != null) {
            persistDir = resource.toURI().getPath();
        }
        // delete prior ssl-client.xml file
        resource = NegativeSSLAndKerberosHiveHookIT.class.getResource("/" + SecurityProperties.SSL_CLIENT_PROPERTIES);
        if (resource != null) {
            File sslClientFile = new File(persistDir, SecurityProperties.SSL_CLIENT_PROPERTIES);
            if (sslClientFile != null && sslClientFile.exists()) {
                sslClientFile.delete();
            }
        }
        setupKDCAndPrincipals();
        setupCredentials();

        // client will actually only leverage subset of these properties
        final PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.setProperty(TLS_ENABLED, true);
        configuration.setProperty(TRUSTSTORE_FILE_KEY, "../../webapp/target/metadata.keystore");
        configuration.setProperty(KEYSTORE_FILE_KEY, "../../webapp/target/metadata.keystore");
        configuration.setProperty(CERT_STORES_CREDENTIAL_PROVIDER_PATH, providerUrl);
        configuration.setProperty("metadata.http.authentication.type", "kerberos");
        configuration.setProperty(SSLFactory.SSL_HOSTNAME_VERIFIER_KEY, SSLHostnameVerifier.DEFAULT_AND_LOCALHOST.toString());

        configuration.save(new FileWriter(persistDir + File.separator + "client.properties"));

        String confLocation = System.getProperty("metadata.conf");
        URL url;
        if (confLocation == null) {
            url = PropertiesUtil.class.getResource("/application.properties");
        } else {
            url = new File(confLocation, "application.properties").toURI().toURL();
        }
        configuration.load(url);
        configuration.setProperty(TLS_ENABLED, true);
        configuration.setProperty("metadata.http.authentication.enabled", "true");
        configuration.setProperty("metadata.http.authentication.kerberos.principal", "HTTP/localhost@" + kdc.getRealm());
        configuration.setProperty("metadata.http.authentication.kerberos.keytab", httpKeytabFile.getAbsolutePath());
        configuration.setProperty("metadata.http.authentication.kerberos.name.rules",
                "RULE:[1:$1@$0](.*@EXAMPLE.COM)s/@.*//\nDEFAULT");

        configuration.save(new FileWriter(persistDir + File.separator + "application.properties"));

        secureEmbeddedServer = new TestSecureEmbeddedServer(21443, "webapp/target/metadata-governance") {
            @Override
            public PropertiesConfiguration getConfiguration() {
                return configuration;
            }
        };
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar(System.getProperty("user.dir") + getWarPath());
        secureEmbeddedServer.getServer().setHandler(webapp);

        // save original setting
        originalConf = System.getProperty("metadata.conf");
        System.setProperty("metadata.conf", persistDir);
        secureEmbeddedServer.getServer().start();

    }

    @AfterClass
    public void tearDown() throws Exception {
        if (secureEmbeddedServer != null) {
            secureEmbeddedServer.getServer().stop();
        }

        if (kdc != null) {
            kdc.stop();
        }

        if (originalConf != null) {
            System.setProperty("metadata.conf", originalConf);
        }
    }

    private void runCommand(final String cmd) throws Exception {
        ss.setCommandType(null);
        driver.run(cmd);
        Assert.assertNotNull(driver.getErrorMsg());
        Assert.assertTrue(driver.getErrorMsg().contains("Mechanism level: Failed to find any Kerberos tgt"));
    }

    @Test
    public void testUnsecuredCreateDatabase() throws Exception {
        String dbName = "db" + RandomStringUtils.randomAlphanumeric(5).toLowerCase();
        runCommand("create database " + dbName);
    }

}