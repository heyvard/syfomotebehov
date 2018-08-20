package no.nav.syfo.util;

import static java.lang.System.getProperty;

public class RestUtils {

    //TODO Hent fra Fasit via property i nais.yaml i stedet
    public static String baseUrl() {
        return "https://app" + miljo() + ".adeo.no";
    }

    private static String miljo() {
        if ("p".equals(getProperty("environment.name"))) {
            return "";
        }
        return "-" + getProperty("environment.name");
    }

}
