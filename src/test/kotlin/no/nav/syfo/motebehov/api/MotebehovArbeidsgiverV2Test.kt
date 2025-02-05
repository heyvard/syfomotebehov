package no.nav.syfo.motebehov.api

import java.time.LocalDate
import java.util.function.Consumer
import javax.inject.Inject
import no.nav.security.token.support.core.context.TokenValidationContextHolder
import no.nav.syfo.LocalApplication
import no.nav.syfo.consumer.aktorregister.AktorregisterConsumer
import no.nav.syfo.consumer.aktorregister.domain.Fodselsnummer
import no.nav.syfo.consumer.azuread.v2.AzureAdV2TokenConsumer
import no.nav.syfo.consumer.brukertilgang.BrukertilgangConsumer
import no.nav.syfo.consumer.pdl.PdlConsumer
import no.nav.syfo.consumer.sts.StsConsumer
import no.nav.syfo.motebehov.MotebehovSvar
import no.nav.syfo.motebehov.api.internad.v2.MotebehovVeilederADControllerV2
import no.nav.syfo.motebehov.database.MotebehovDAO
import no.nav.syfo.motebehov.motebehovstatus.DAYS_END_SVAR_BEHOV
import no.nav.syfo.motebehov.motebehovstatus.DAYS_START_SVAR_BEHOV
import no.nav.syfo.motebehov.motebehovstatus.MotebehovSkjemaType
import no.nav.syfo.motebehov.motebehovstatus.MotebehovStatus
import no.nav.syfo.oppfolgingstilfelle.database.OppfolgingstilfelleDAO
import no.nav.syfo.oversikthendelse.OversikthendelseProducer
import no.nav.syfo.testhelper.OidcTestHelper.loggInnBruker
import no.nav.syfo.testhelper.OidcTestHelper.loggInnVeilederADV2
import no.nav.syfo.testhelper.OidcTestHelper.loggUtAlle
import no.nav.syfo.testhelper.UserConstants.ARBEIDSTAKER_AKTORID
import no.nav.syfo.testhelper.UserConstants.ARBEIDSTAKER_FNR
import no.nav.syfo.testhelper.UserConstants.LEDER_AKTORID
import no.nav.syfo.testhelper.UserConstants.LEDER_FNR
import no.nav.syfo.testhelper.UserConstants.VEILEDER_ID
import no.nav.syfo.testhelper.UserConstants.VIRKSOMHETSNUMMER
import no.nav.syfo.testhelper.UserConstants.VIRKSOMHETSNUMMER_2
import no.nav.syfo.testhelper.assertion.assertMotebehovStatus
import no.nav.syfo.testhelper.clearCache
import no.nav.syfo.testhelper.generator.MotebehovGenerator
import no.nav.syfo.testhelper.generator.generateOversikthendelsetilfelle
import no.nav.syfo.testhelper.generator.generatePdlHentPerson
import no.nav.syfo.testhelper.generator.generateStsToken
import no.nav.syfo.testhelper.mockAndExpectBehandlendeEnhetRequest
import no.nav.syfo.testhelper.mockAndExpectBehandlendeEnhetRequestWithTilgangskontroll
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cache.CacheManager
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate

@ExtendWith(SpringExtension::class)
@SpringBootTest(classes = [LocalApplication::class])
@DirtiesContext
class MotebehovArbeidsgiverV2Test {
    @Value("\${azure.openid.config.token.endpoint}")
    private lateinit var azureTokenEndpoint: String

    @Value("\${syfobehandlendeenhet.url}")
    private lateinit var behandlendeenhetUrl: String

    @Value("\${tilgangskontrollapi.url}")
    private lateinit var tilgangskontrollUrl: String

    @Inject
    private lateinit var motebehovArbeidsgiverController: MotebehovArbeidsgiverV2Controller

    @Inject
    private lateinit var motebehovVeilederController: MotebehovVeilederADControllerV2

    @Inject
    private lateinit var contextHolder: TokenValidationContextHolder

    @Inject
    private lateinit var motebehovDAO: MotebehovDAO

    @Inject
    private lateinit var cacheManager: CacheManager

    @Inject
    private lateinit var oppfolgingstilfelleDAO: OppfolgingstilfelleDAO

    @Inject
    @Qualifier("restTemplateWithProxy")
    private lateinit var restTemplateWithProxy: RestTemplate
    private lateinit var mockRestServiceWithProxyServer: MockRestServiceServer

    @Inject
    private lateinit var restTemplate: RestTemplate

    @MockBean
    private lateinit var aktorregisterConsumer: AktorregisterConsumer

    @MockBean
    private lateinit var pdlConsumer: PdlConsumer

    @MockBean
    private lateinit var brukertilgangConsumer: BrukertilgangConsumer

    @MockBean
    private lateinit var stsConsumer: StsConsumer

    @MockBean
    private lateinit var oversikthendelseProducer: OversikthendelseProducer

    private lateinit var mockRestServiceServer: MockRestServiceServer

    private val motebehovGenerator = MotebehovGenerator()

    private val stsToken = generateStsToken().access_token

    @BeforeEach
    fun setUp() {
        `when`(aktorregisterConsumer.getAktorIdForFodselsnummer(Fodselsnummer(ARBEIDSTAKER_FNR))).thenReturn(
            ARBEIDSTAKER_AKTORID
        )
        `when`(aktorregisterConsumer.getAktorIdForFodselsnummer(Fodselsnummer(LEDER_FNR))).thenReturn(LEDER_AKTORID)
        `when`(brukertilgangConsumer.hasAccessToAnsatt(ARBEIDSTAKER_FNR)).thenReturn(true)
        `when`(pdlConsumer.person(Fodselsnummer(ARBEIDSTAKER_FNR))).thenReturn(generatePdlHentPerson(null, null))
        `when`(stsConsumer.token()).thenReturn(stsToken)
        mockRestServiceServer = MockRestServiceServer.bindTo(restTemplate).build()
        mockRestServiceWithProxyServer = MockRestServiceServer.bindTo(restTemplateWithProxy).build()
        loggInnBruker(contextHolder, LEDER_FNR)
        cleanDB()
    }

    @AfterEach
    fun tearDown() {
        loggUtAlle(contextHolder)
        resetMockRestServers()
        cacheManager.cacheNames
            .forEach(
                Consumer { cacheName: String ->
                    val cache = cacheManager.getCache(cacheName)
                    cache?.clear()
                }
            )
        cleanDB()
        AzureAdV2TokenConsumer.Companion.clearCache()
    }

    @Test
    fun getMotebehovStatusWithNoOppfolgingstilfelle() {
        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(false, null, null)
    }

    @Test
    fun getMotebehovStatusWithTodayOutsideOppfolgingstilfelleStart() {
        loggUtAlle(contextHolder)
        loggInnBruker(contextHolder, ARBEIDSTAKER_FNR)

        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().plusDays(1),
                tom = LocalDate.now().plusDays(10)
            )
        )
        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(false, null, null)
    }

    @Test
    fun getMotebehovStatusWithTodayOutsideOppfolgingstilfelleEnd() {
        loggUtAlle(contextHolder)
        loggInnBruker(contextHolder, ARBEIDSTAKER_FNR)

        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(10),
                tom = LocalDate.now().minusDays(1)
            )
        )
        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(false, null, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMergedByActiveAndExpiredOppfolgingstilfelleNoOverlapVirksomhetWithoutActiveOppfolgingstilfelle() {
        val activeOppfolgingstilfelleStartDate = LocalDate.now().minusDays(DAYS_START_SVAR_BEHOV).plusDays(1)
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER,
                fom = activeOppfolgingstilfelleStartDate.minusDays(2),
                tom = activeOppfolgingstilfelleStartDate.minusDays(1)
            )
        )
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER_2,
                fom = activeOppfolgingstilfelleStartDate,
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(false, null, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMergedByActiveAndExpiredOppfolgingstilfelleNoOverlap() {
        val activeOppfolgingstilfelleStartDate = LocalDate.now().minusDays(DAYS_START_SVAR_BEHOV).plusDays(1)
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER_2,
                fom = activeOppfolgingstilfelleStartDate.minusDays(2),
                tom = activeOppfolgingstilfelleStartDate.minusDays(1)
            )
        )
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER,
                fom = activeOppfolgingstilfelleStartDate,
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMergedByActiveAndExpiredOppfolgingstilfelleWithOverlap() {
        val activeOppfolgingstilfelleStartDate = LocalDate.now().minusDays(DAYS_START_SVAR_BEHOV).plusDays(1)
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER_2,
                fom = activeOppfolgingstilfelleStartDate.minusDays(2),
                tom = activeOppfolgingstilfelleStartDate
            )
        )
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER,
                fom = activeOppfolgingstilfelleStartDate,
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.SVAR_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMergedBy2Oppfolgingstilfeller() {
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER,
                fom = LocalDate.now().minusDays(DAYS_END_SVAR_BEHOV).minusDays(1),
                tom = LocalDate.now()
            )
        )
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                virksomhetsnummer = VIRKSOMHETSNUMMER_2,
                fom = LocalDate.now().minusDays(2),
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleDay1() {
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now(),
                tom = LocalDate.now()
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleLastDay() {
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_END_SVAR_BEHOV).minusDays(1),
                tom = LocalDate.now()
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMedBehov() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now(),
            tom = LocalDate.now().plusDays(1)
        )
        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMeldBehovSubmittedAndBehandlet() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now(),
            tom = LocalDate.now().plusDays(1)
        )
        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovSvar = motebehovGenerator.lagMotebehovSvar(true)
        submitMotebehovAndSendOversikthendelse(motebehovSvar)

        resetMockRestServers()
        loggUtAlle(contextHolder)
        loggInnVeilederADV2(contextHolder, VEILEDER_ID)

        mockBehandlendEnhetWithTilgangskontroll(ARBEIDSTAKER_FNR)
        motebehovVeilederController.behandleMotebehov(ARBEIDSTAKER_FNR)

        resetMockRestServers()
        loggInnBruker(contextHolder, LEDER_FNR)
        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleMeldBehovMoteplanleggerActiveMeldBehovSubmitted() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now(),
            tom = LocalDate.now().plusDays(1)
        )
        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovSvar = motebehovGenerator.lagMotebehovSvar(true)
        submitMotebehovAndSendOversikthendelse(motebehovSvar)

        mockRestServiceServer.reset()

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, motebehovSvar)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleBeforeSvarBehovStartDate() {
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_START_SVAR_BEHOV).plusDays(1),
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleAfterSvarBehovEndDate() {
        oppfolgingstilfelleDAO.create(
            generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_END_SVAR_BEHOV),
                tom = LocalDate.now().plusDays(1)
            )
        )

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.MELD_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovInsideSvarBehovUpperLimit() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now().minusDays(DAYS_END_SVAR_BEHOV).plusDays(1),
            tom = LocalDate.now().plusDays(1)
        )

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.SVAR_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithSvarBehovAndMoteCreated() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now().minusDays(DAYS_END_SVAR_BEHOV).plusDays(1),
            tom = LocalDate.now().plusDays(1)
        )
        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovSvar = motebehovGenerator.lagMotebehovSvar(true)
        submitMotebehovAndSendOversikthendelse(motebehovSvar)

        mockRestServiceServer.reset()

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.SVAR_BEHOV, motebehovSvar)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndNoMoteInsideSvarBehovLowerLimit() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
            fom = LocalDate.now().minusDays(DAYS_START_SVAR_BEHOV),
            tom = LocalDate.now().plusDays(1)
        )

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.SVAR_BEHOV, null)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndNoMote() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle
        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(ARBEIDSTAKER_FNR, VIRKSOMHETSNUMMER)
            .assertMotebehovStatus(true, MotebehovSkjemaType.SVAR_BEHOV, null)
    }
    @Test
    fun getMotebehovStatusAndSendOversikthendelseWithMotebehovHarBehovTrue() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle)
        lagreOgHentMotebehovOgSendOversikthendelse(harBehov = true)
    }

    @Test
    fun getMotebehovStatusAndSendOversikthendelseWithMotebehovHarBehovFalse() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle)
        lagreOgHentMotebehovOgSendOversikthendelse(false)
    }

    private fun submitMotebehovAndSendOversikthendelse(motebehovSvar: MotebehovSvar) {
        mockAndExpectBehandlendeEnhetRequest(
            azureTokenEndpoint,
            mockRestServiceWithProxyServer,
            behandlendeenhetUrl,
            ARBEIDSTAKER_FNR
        )

        motebehovArbeidsgiverController.lagreMotebehovArbeidsgiver(
            motebehovGenerator.lagNyttMotebehovArbeidsgiver().copy(
                motebehovSvar = motebehovSvar
            )
        )
        if (motebehovSvar.harMotebehov) {
            Mockito.verify(oversikthendelseProducer).sendOversikthendelse(any(), any())
        } else {
            Mockito.verify(oversikthendelseProducer, Mockito.never()).sendOversikthendelse(any(), any())
        }
    }

    private fun lagreOgHentMotebehovOgSendOversikthendelse(harBehov: Boolean) {
        mockAndExpectBehandlendeEnhetRequest(
            azureTokenEndpoint,
            mockRestServiceWithProxyServer,
            behandlendeenhetUrl,
            ARBEIDSTAKER_FNR
        )

        val motebehovSvar = motebehovGenerator.lagMotebehovSvar(harBehov)

        motebehovArbeidsgiverController.lagreMotebehovArbeidsgiver(
            motebehovGenerator.lagNyttMotebehovArbeidsgiver().copy(
                motebehovSvar = motebehovSvar
            )
        )
        if (!harBehov) {
            mockRestServiceServer.reset()
        }

        val motebehovStatus: MotebehovStatus = motebehovArbeidsgiverController.motebehovStatusArbeidsgiver(
            ARBEIDSTAKER_FNR,
            VIRKSOMHETSNUMMER
        )

        assertTrue(motebehovStatus.visMotebehov)
        assertEquals(MotebehovSkjemaType.SVAR_BEHOV, motebehovStatus.skjemaType)
        val motebehov = motebehovStatus.motebehov!!
        assertNotNull(motebehov)
        Assertions.assertThat(motebehov.opprettetAv).isEqualTo(LEDER_AKTORID)
        Assertions.assertThat(motebehov.arbeidstakerFnr).isEqualTo(ARBEIDSTAKER_FNR)
        Assertions.assertThat(motebehov.virksomhetsnummer).isEqualTo(VIRKSOMHETSNUMMER)
        Assertions.assertThat(motebehov.skjemaType).isEqualTo(motebehovStatus.skjemaType)
        Assertions.assertThat(motebehov.motebehovSvar).isEqualToComparingFieldByField(motebehovSvar)
        if (harBehov) {
            Mockito.verify(oversikthendelseProducer).sendOversikthendelse(any(), any())
        } else {
            Mockito.verify(oversikthendelseProducer, Mockito.never()).sendOversikthendelse(any(), any())
        }
    }

    private fun resetMockRestServers() {
        mockRestServiceServer.reset()
        mockRestServiceWithProxyServer.reset()
    }
    private fun cleanDB() {
        motebehovDAO.nullstillMotebehov(ARBEIDSTAKER_AKTORID)
        oppfolgingstilfelleDAO.nullstillOppfolgingstilfeller(Fodselsnummer(ARBEIDSTAKER_FNR))
    }
    private fun mockBehandlendEnhetWithTilgangskontroll(fnr: String) {
        mockAndExpectBehandlendeEnhetRequestWithTilgangskontroll(
            azureTokenEndpoint,
            mockRestServiceWithProxyServer,
            mockRestServiceServer,
            behandlendeenhetUrl,
            tilgangskontrollUrl,
            fnr
        )
    }
}

private fun <T> any(): T {
    Mockito.any<T>()
    return uninitialized()
}

private fun <T> uninitialized(): T = null as T
