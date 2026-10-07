package bo.edu.electoral.service;

import bo.edu.electoral.config.DatabaseConnection;
import bo.edu.electoral.dao.ActaDAO;
import bo.edu.electoral.dao.DetalleVotoDAO;
import bo.edu.electoral.dao.MesaDAO;
import bo.edu.electoral.dao.PadronCiudadanoDAO;
import bo.edu.electoral.dao.PapeletaEscrutinioDAO;
import bo.edu.electoral.dao.PartidoPoliticoDAO;
import bo.edu.electoral.model.Acta;
import bo.edu.electoral.model.DetalleVoto;
import bo.edu.electoral.model.Mesa;
import bo.edu.electoral.model.PapeletaEscrutinio;
import bo.edu.electoral.model.PartidoPolitico;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consolida las papeletas de una mesa en su acta oficial y bloquea nuevos votos.
 */
public class CierreMesaService {

    private final ActaDAO actaDAO = new ActaDAO();
    private final DetalleVotoDAO detalleVotoDAO = new DetalleVotoDAO();
    private final MesaDAO mesaDAO = new MesaDAO();
    private final PadronCiudadanoDAO padronDAO = new PadronCiudadanoDAO();
    private final PapeletaEscrutinioDAO papeletaDAO = new PapeletaEscrutinioDAO();
    private final PartidoPoliticoDAO partidoDAO = new PartidoPoliticoDAO();
    private final ValidadorActaService validadorActaService = new ValidadorActaService();

    public ResumenCierre cerrarMesa(int idMesa) throws SQLException, VotacionException {
        Mesa mesa = mesaDAO.findById(idMesa);
        if (mesa == null) {
            throw new VotacionException("No existe una mesa con ID " + idMesa + ".");
        }
        if (!Mesa.ESTADO_HABILITADA.equals(mesa.getEstado())) {
            throw new VotacionException("La mesa " + mesa.getNumeroMesa()
                    + " no se puede cerrar porque está " + mesa.getEstado() + ".");
        }
        for (Acta acta : actaDAO.findAll()) {
            if (acta.getIdMesa() == idMesa) {
                throw new VotacionException("La mesa " + mesa.getNumeroMesa()
                        + " ya tiene un acta registrada.");
            }
        }

        List<PartidoPolitico> partidos = partidoDAO.findAll();
        Map<Integer, Integer> votosPorPartido = new LinkedHashMap<>();
        for (PartidoPolitico partido : partidos) {
            votosPorPartido.put(partido.getIdPartido(), 0);
        }

        int blancos = 0;
        int nulos = 0;
        int total = 0;
        for (PapeletaEscrutinio papeleta : papeletaDAO.findAll()) {
            if (papeleta.getIdMesa() != idMesa) {
                continue;
            }
            total = Math.incrementExact(total);
            if (PapeletaEscrutinio.TIPO_BLANCO.equals(papeleta.getTipoVoto())) {
                blancos = Math.incrementExact(blancos);
            } else if (PapeletaEscrutinio.TIPO_NULO.equals(papeleta.getTipoVoto())) {
                nulos = Math.incrementExact(nulos);
            } else if (PapeletaEscrutinio.TIPO_VALIDO.equals(papeleta.getTipoVoto())) {
                Integer idPartido = papeleta.getIdPartido();
                if (idPartido == null || !votosPorPartido.containsKey(idPartido)) {
                    throw new VotacionException("La papeleta " + papeleta.getIdPapeleta()
                            + " no tiene un partido válido registrado.");
                }
                votosPorPartido.put(idPartido, Math.incrementExact(votosPorPartido.get(idPartido)));
            } else {
                throw new VotacionException("La papeleta " + papeleta.getIdPapeleta()
                        + " tiene un tipo de voto no válido.");
            }
        }
        if (total == 0) {
            throw new VotacionException("La mesa " + mesa.getNumeroMesa()
                    + " no tiene papeletas para realizar el escrutinio.");
        }
        int votosValidos = 0;
        for (int votos : votosPorPartido.values()) {
            votosValidos = Math.addExact(votosValidos, votos);
        }
        validadorActaService.validar(
                mesa,
                blancos,
                nulos,
                votosValidos,
                total,
                padronDAO.contarVotantesPorMesa(idMesa));

        Connection connection = DatabaseConnection.getConnection();
        boolean autoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);

            Acta acta = new Acta();
            acta.setIdMesa(idMesa);
            acta.setVotosBlancos(blancos);
            acta.setVotosNulos(nulos);
            acta.setTotalCiudadanosVotaron(total);
            actaDAO.insert(acta);

            for (Map.Entry<Integer, Integer> votos : votosPorPartido.entrySet()) {
                DetalleVoto detalle = new DetalleVoto();
                detalle.setIdActa(acta.getIdActa());
                detalle.setIdPartido(votos.getKey());
                detalle.setVotosValidos(votos.getValue());
                detalleVotoDAO.insert(detalle);
            }

            mesa.setEstado(Mesa.ESTADO_COMPUTADA);
            if (!mesaDAO.update(mesa)) {
                throw new SQLException("No se pudo marcar la mesa como COMPUTADA.");
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException errorRollback) {
                e.addSuppressed(errorRollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }

        return new ResumenCierre(mesa.getNumeroMesa(), total, votosValidos, blancos, nulos);
    }

    public ResultadoCierreMasivo cerrarTodasMesas() throws SQLException {
        List<ResumenCierre> cerradas = new java.util.ArrayList<>();
        List<String> noCerradas = new java.util.ArrayList<>();
        for (Mesa mesa : mesaDAO.findAll()) {
            if (!Mesa.ESTADO_HABILITADA.equals(mesa.getEstado())) {
                continue;
            }
            try {
                cerradas.add(cerrarMesa(mesa.getIdMesa()));
            } catch (VotacionException e) {
                noCerradas.add("Mesa " + mesa.getNumeroMesa() + ": " + e.getMessage());
            }
        }
        return new ResultadoCierreMasivo(cerradas, noCerradas);
    }

    public record ResumenCierre(
            int numeroMesa,
            int totalVotos,
            int votosValidos,
            int votosBlancos,
            int votosNulos
    ) {
    }

    public record ResultadoCierreMasivo(List<ResumenCierre> cerradas, List<String> noCerradas) {
        public ResultadoCierreMasivo {
            cerradas = List.copyOf(cerradas);
            noCerradas = List.copyOf(noCerradas);
        }
    }
}
