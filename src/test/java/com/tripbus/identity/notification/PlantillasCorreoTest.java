package com.tripbus.identity.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlantillasCorreoTest {

    private final PlantillasCorreo plantillas = new PlantillasCorreo("https://tripbus.co/");

    @Test
    void losEnlacesApuntanAlFrontendSinDobleBarra() {
        assertThat(plantillas.enlaceRestablecer("abc")).isEqualTo("https://tripbus.co/restablecer?token=abc");
        assertThat(plantillas.enlaceVerificar("abc")).isEqualTo("https://tripbus.co/verificar-correo?token=abc");
        assertThat(plantillas.enlaceRecuperar()).isEqualTo("https://tripbus.co/recuperar");
        assertThat(new PlantillasCorreo("http://localhost:5173").enlaceRecuperar())
                .isEqualTo("http://localhost:5173/recuperar");
    }

    @Test
    void elSaludoUsaElPrimerNombre() {
        assertThat(PlantillasCorreo.saludo("  Ana María Pérez ")).isEqualTo("Hola, Ana.");
        assertThat(PlantillasCorreo.saludo(" ")).isEqualTo("Hola.");
        assertThat(PlantillasCorreo.saludo(null)).isEqualTo("Hola.");
    }

    @Test
    void recuperacionEnTextoYHtmlConTuteoYElEnlace() {
        Correo correo = plantillas.recuperacion("Ana Pérez", "tok123");

        assertThat(correo.asunto()).isEqualTo("Crea una nueva contraseña en TripBus");
        assertThat(correo.texto())
                .startsWith("Hola, Ana.\n\nPara crear una nueva contraseña")
                .contains("https://tripbus.co/restablecer?token=tok123", "15 minutos");
        assertThat(correo.html())
                .contains("<p>Hola, Ana.</p>", "href=\"https://tripbus.co/restablecer?token=tok123\"", "Crear contraseña");
    }

    @Test
    void avisoDeBloqueoInformaElMotivoYEnlazaALaRecuperacion() {
        Correo correo=plantillas.avisoBloqueoCuenta("Ana", "tok-lock");
        assertThat(correo.asunto()).contains("Bloqueamos temporalmente");
        assertThat(correo.texto()).contains("varios intentos fallidos", "https://tripbus.co/restablecer?token=tok-lock", "15 minutos");
        assertThat(correo.html()).contains("href=\"https://tripbus.co/restablecer?token=tok-lock\"");
    }

    @Test
    void verificacionLlevaElEnlaceDeVerificarCorreo() {
        Correo correo = plantillas.verificacion("Luis", "tok456");

        assertThat(correo.asunto()).isEqualTo("Confirma tu correo en TripBus");
        assertThat(correo.texto()).startsWith("Hola, Luis.")
                .contains("https://tripbus.co/verificar-correo?token=tok456", "24 horas");
        assertThat(correo.html()).contains("https://tripbus.co/verificar-correo?token=tok456");
    }

    @Test
    void avisoDeCambioEnlazaALaRecuperacion() {
        Correo correo = plantillas.avisoCambioContrasena("Ana");

        assertThat(correo.texto())
                .contains("Tu contraseña cambió", "Si no fuiste tú, recupérala aquí", "https://tripbus.co/recuperar");
        assertThat(correo.html()).contains("href=\"https://tripbus.co/recuperar\"");
    }

    @Test
    void elNombreSeEscapaEnElHtml() {
        Correo correo = plantillas.avisoCambioContrasena("<script>alert(1)</script>");

        assertThat(correo.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }
}
