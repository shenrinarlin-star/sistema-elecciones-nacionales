package bo.edu.electoral.service;

import bo.edu.electoral.model.Mesa;

/**
 SIIII Valida la consistencia entre el acta, las papeletas, el padrón y los inscritos.
 */
public class ValidadorActaService {

    public void validar(
            Mesa mesa,
            int votosBlancos,
            int votosNulos,
            int votosValidos,
            int totalPapeletas,
            long ciudadanosQueVotaron
    ) throws VotacionException {
        if (mesa == null) {
            throw new VotacionException("No se puede validar el acta sin una mesa.");
        }
        if (votosBlancos < 0 || votosNulos < 0 || votosValidos < 0
                || totalPapeletas < 0 || ciudadanosQueVotaron < 0) {
            throw new VotacionException("El acta contiene cantidades negativas.");
        }
        if (mesa.getCantidadInscritos() < 0) {
            throw new VotacionException("La mesa " + mesa.getNumeroMesa()
                    + " tiene una cantidad de inscritos negativa.");
        }

        long sumaVotos = (long) votosBlancos + votosNulos + votosValidos;
        if (sumaVotos != totalPapeletas) {
            throw new VotacionException("El acta de la mesa " + mesa.getNumeroMesa()
                    + " no coincide con las papeletas: blancos + nulos + válidos = "
                    + sumaVotos + ", papeletas registradas = " + totalPapeletas + ".");
        }
        if (totalPapeletas > mesa.getCantidadInscritos()) {
            throw new VotacionException("La mesa " + mesa.getNumeroMesa() + " registra "
                    + totalPapeletas + " papeletas, más que sus " + mesa.getCantidadInscritos()
                    + " ciudadanos inscritos.");
        }
        if (totalPapeletas != ciudadanosQueVotaron) {
            throw new VotacionException("La mesa " + mesa.getNumeroMesa() + " tiene "
                    + totalPapeletas + " papeletas, pero " + ciudadanosQueVotaron
                    + " ciudadanos marcados como votantes en el padrón.");
        }
    }
}
