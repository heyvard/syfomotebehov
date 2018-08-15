package no.nav.syfo.controller;

import lombok.extern.slf4j.Slf4j;
import no.nav.security.oidc.OIDCConstants;
import no.nav.security.oidc.context.OIDCRequestContextHolder;
import no.nav.security.oidc.context.OIDCValidationContext;
import no.nav.security.spring.oidc.validation.api.ProtectedWithClaims;
import no.nav.syfo.consumer.ws.AktoerConsumer;
import no.nav.syfo.domain.rest.LagreMotebehov;
import no.nav.syfo.domain.rest.Motebehov;
import no.nav.syfo.repository.dao.MotebehovDAO;
import no.nav.syfo.util.Toggle;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import javax.validation.ConstraintViolationException;
import javax.validation.Valid;
import javax.validation.constraints.Pattern;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static no.nav.syfo.mappers.PersistencyMappers.rsMotebehov2p;
import static no.nav.syfo.mappers.RestMappers.motebehov2rs;
import static no.nav.syfo.util.MapUtil.map;
import static no.nav.syfo.util.MapUtil.mapListe;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@RestController
@ProtectedWithClaims(issuer = "selvbetjening", claimMap = {"acr=Level4"})
@RequestMapping(value = "/api/motebehov")
public class MotebehovController {

    private OIDCRequestContextHolder contextHolder;
    private AktoerConsumer aktoerConsumer;
    private MotebehovDAO motebehovDAO;

    public MotebehovController(final OIDCRequestContextHolder contextHolder,
                               final AktoerConsumer aktoerConsumer,
                               final MotebehovDAO motebehovDAO) {
        this.contextHolder = contextHolder;
        this.aktoerConsumer = aktoerConsumer;
        this.motebehovDAO = motebehovDAO;
    }

    @ResponseBody
    @GetMapping(produces = APPLICATION_JSON_VALUE)
    public List<Motebehov> hentMotebehovListe(@RequestParam(name = "fnr") @Pattern(regexp = "^[0-9]{11}$") String arbeidstakerFnr) {
        if (Toggle.endepunkterForMotebehov) {
            return mapListe(motebehovDAO.hentMotebehovListeForAktoer(aktoerConsumer.hentAktoerIdForFnr(arbeidstakerFnr)), motebehov2rs);
        } else {
            log.info("Det ble gjort kall mot 'motebehov', men dette endepunktet er togglet av.");
            return null;
        }
    }

    @ResponseBody
    @PostMapping(consumes = APPLICATION_JSON_VALUE, produces = APPLICATION_JSON_VALUE)
    public UUID lagreMotebehov(@RequestBody @Valid LagreMotebehov lagreMotebehov) {
        if (Toggle.endepunkterForMotebehov) {
            Motebehov motebehov = mapLagremotebehovTilMotebehov(lagreMotebehov);

            return motebehovDAO.create(map(motebehov, rsMotebehov2p));
        } else {
            log.info("Det ble gjort kall mot 'motebehov', men dette endepunktet er togglet av.");
            return null;
        }
    }

    private Motebehov mapLagremotebehovTilMotebehov(LagreMotebehov lagreMotebehov) {
        String innloggetAktoerId = aktoerConsumer.hentAktoerIdForFnr(fnrFraOIDC());

        return new Motebehov()
                .opprettetAv(innloggetAktoerId)
                .arbeidstaker(lagreMotebehov.arbeidstakerFnr)
                .virksomhetsnummer(lagreMotebehov.virksomhetsnummer)
                .motebehovSvar(lagreMotebehov.motebehovSvar());
    }

    private String fnrFraOIDC() {
        OIDCValidationContext context = (OIDCValidationContext) contextHolder
                .getRequestAttribute(OIDCConstants.OIDC_VALIDATION_CONTEXT);
        return context.getClaims("selvbetjening").getClaimSet().getSubject();
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class})
    void handleBadRequests(HttpServletResponse response) throws IOException {
        response.sendError(BAD_REQUEST.value(), "Vi kunne ikke tolke inndataene :/");
    }

}
