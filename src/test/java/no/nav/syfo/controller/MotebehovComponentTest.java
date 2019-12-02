package no.nav.syfo.controller;

import no.nav.security.oidc.context.OIDCRequestContextHolder;
import no.nav.syfo.LocalApplication;
import no.nav.syfo.domain.rest.Motebehov;
import no.nav.syfo.domain.rest.MotebehovSvar;
import no.nav.syfo.kafka.producer.OversikthendelseProducer;
import no.nav.syfo.kafka.producer.model.KOversikthendelse;
import no.nav.syfo.repository.dao.MotebehovDAO;
import no.nav.syfo.sts.StsConsumer;
import no.nav.syfo.testhelper.MotebehovGenerator;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import javax.inject.Inject;
import java.util.List;

import static no.nav.syfo.testhelper.OidcTestHelper.loggInnBruker;
import static no.nav.syfo.testhelper.OidcTestHelper.loggUtAlle;
import static no.nav.syfo.testhelper.RestHelperKt.mockAndExpectBehandlendeEnhetRequest;
import static no.nav.syfo.testhelper.RestHelperKt.mockAndExpectSTSService;
import static no.nav.syfo.testhelper.UserConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Komponent / blackbox test av møtebehovsfunskjonaliteten - test at input til endepunktet (controlleren, for enkelhets skyld)
 * lagres og hentes riktig fra minnedatabasen.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = LocalApplication.class)
@DirtiesContext
public class MotebehovComponentTest {

    @Value("${syfobehandlendeenhet.url}")
    private String behandlendeenhetUrl;

    @Value("${security.token.service.rest.url}")
    private String stsUrl;

    @Value("${srv.username}")
    private String srvUsername;

    @Value("${srv.password}")
    private String srvPassword;

    @Inject
    private MotebehovBrukerController motebehovController;

    @Inject
    private OIDCRequestContextHolder oidcRequestContextHolder;

    @Inject
    private MotebehovDAO motebehovDAO;

    @Inject
    private CacheManager cacheManager;

    @Inject
    private StsConsumer stsConsumer;

    @Inject
    private RestTemplate restTemplate;

    @MockBean
    private OversikthendelseProducer oversikthendelseProducer;

    private MockRestServiceServer mockRestServiceServer;

    private MotebehovGenerator motebehovGenerator = new MotebehovGenerator();

    @Before
    public void setUp() {
        this.mockRestServiceServer = MockRestServiceServer.bindTo(restTemplate).build();
        loggInnBruker(oidcRequestContextHolder, LEDER_FNR);
        cleanDB();
    }

    @After
    public void tearDown() {
        loggUtAlle(oidcRequestContextHolder);
        mockRestServiceServer.reset();
        cacheManager.getCacheNames()
                .forEach(cacheName -> cacheManager.getCache(cacheName).clear());
        cleanDB();
    }

    @Test
    public void lagreOgHentMotebehovOgSendOversikthendelseVedSvarMedBehov() {
        mockSTS();
        mockAndExpectBehandlendeEnhetRequest(mockRestServiceServer, behandlendeenhetUrl, ARBEIDSTAKER_FNR);
        lagreOgHentMotebehovOgSendOversikthendelse(true);
    }

    @Test
    public void lagreOgHentMotebehovOgSendOversikthendelseMedSvarUtenBehov() {
        mockSTS();
        mockAndExpectBehandlendeEnhetRequest(mockRestServiceServer, behandlendeenhetUrl, ARBEIDSTAKER_FNR);
        lagreOgHentMotebehovOgSendOversikthendelse(false);
    }

    private void mockSTS() {
        if (!stsConsumer.isTokenCached()) {
            mockAndExpectSTSService(mockRestServiceServer, stsUrl, srvUsername, srvPassword);
        }
    }

    private void lagreOgHentMotebehovOgSendOversikthendelse(boolean harBehov) {
        final MotebehovSvar motebehovSvar = motebehovGenerator.lagMotebehovSvar(harBehov);

        // Lagre
        motebehovController.lagreMotebehov(motebehovGenerator.lagNyttMotebehovFraAT());

        // Hent
        List<Motebehov> motebehovListe = motebehovController.hentMotebehovListe(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER);
        assertThat(motebehovListe).size().isOne();

        Motebehov motebehov = motebehovListe.get(0);
        assertThat(motebehov.opprettetAv).isEqualTo(LEDER_AKTORID);
        assertThat(motebehov.arbeidstakerFnr).isEqualTo(ARBEIDSTAKER_FNR);
        assertThat(motebehov.virksomhetsnummer).isEqualTo(VIRKSOMHETSNUMMER);
        assertThat(motebehov.motebehovSvar).isEqualToComparingFieldByField(motebehovSvar);

        if (harBehov) {
            verify(oversikthendelseProducer).sendOversikthendelse(any(KOversikthendelse.class));
        } else {
            verify(oversikthendelseProducer, never()).sendOversikthendelse(any(KOversikthendelse.class));
        }
    }

    private void cleanDB() {
        motebehovDAO.nullstillMotebehov(ARBEIDSTAKER_AKTORID);
    }

}
