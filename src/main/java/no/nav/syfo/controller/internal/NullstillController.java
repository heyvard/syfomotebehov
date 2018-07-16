package no.nav.syfo.controller.internal;


import lombok.extern.slf4j.Slf4j;
import no.nav.security.spring.oidc.validation.api.Unprotected;
import no.nav.syfo.repository.dao.DialogmotebehovDAO;
import no.nav.syfo.util.Toggle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@Slf4j
@RestController
@Unprotected
@RequestMapping(value = "/internal")
public class NullstillController {

    private final DialogmotebehovDAO dialogmotebehovDAO;

    public NullstillController(DialogmotebehovDAO dialogmotebehovDAO) {
        this.dialogmotebehovDAO = dialogmotebehovDAO;
    }

    @ResponseBody
    @RequestMapping(value = "/nullstill/{aktoerId}", produces = APPLICATION_JSON_VALUE)
    public String slettMotebehov(@PathVariable String aktoerId, @Value("${fasit.environment.name:ukjent}") String env) {
        if (Toggle.enableNullstill || Stream.of("q1", "local").anyMatch(env::equals)) {
            log.info("Sletter alle dialogmøtebehov på aktørid: {}", aktoerId);
            int antallSlettedeDialogmotebehov = dialogmotebehovDAO.nullstillMotebehov(aktoerId);
            return "Slettet " + antallSlettedeDialogmotebehov + " dialogmøtebehov.";
        } else {
            log.info("Det ble gjort kall mot 'nullstill', men dette endepunktet er togglet av og skal aldri brukes i prod.");
            return "Toggle er av, eller er sletting ikke tilgjengelig i " + env;
        }
    }
}