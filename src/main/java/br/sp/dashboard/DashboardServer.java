package br.sp.dashboard;

import br.sp.readPDF.BoletimProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servidor web da Versao_Java.
 *
 * Rotas (porta padrão 8080, configurável via env PORT):
 *   GET  /                  -> dashboard.html (layout do DashboardApp JavaFX)
 *   GET  /integracao        -> integracao.html (layout Integração PDF)
 *   GET  /dashboards        -> dashboards.html
 *   GET  /api/dados         -> {"arquivo": ..., "alunos": [...]}
 *   POST /api/dados         -> salva {"alunos": [...]} em dados_alunos.json
 *   POST /api/processar-pdfs-> multipart: pdfs (vários), napne, turma, substituir
 *
 * Ao iniciar, se dados_alunos.json não existir, processa automaticamente:
 *   boletins_alunos.pdf + BoletinsDuas2026.pdf (boletins)
 *   DadosAmorimEditados1 (1).pdf (NAPNE)
 */
public class DashboardServer {

    private static final String DADOS_JSON = "dados_alunos.json";
    private static final String UPLOAD_DIR = "uploads_tmp";
    private static final int PORTA_PADRAO = 8080;

    private static final List<String> PDFs_BOLETINS = Arrays.asList("boletins_alunos.pdf", "BoletinsDuas2026.pdf");
    private static final String PDF_NAPNE = "DadosAmorimEditados1 (1).pdf";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static Path baseDir() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Path dadosJson() {
        return baseDir().resolve(DADOS_JSON);
    }

    private static Path uploadDir() {
        return baseDir().resolve(UPLOAD_DIR);
    }

    /** Localiza um PDF: primeiro no diretório de trabalho, depois no classpath (/pdfs/). */
    private static File localizarPdf(String nome) throws IOException {
        File local = baseDir().resolve(nome).toFile();
        if (local.isFile()) {
            return local;
        }
        String recurso = "/pdfs/" + nome;
        try (InputStream in = DashboardServer.class.getResourceAsStream(recurso)) {
            if (in == null) {
                return null;
            }
            Path destino = uploadDir().resolve("recurso_" + nome.replaceAll("[^A-Za-z0-9._-]", "_"));
            Files.createDirectories(destino.getParent());
            Files.copy(in, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return destino.toFile();
        }
    }

    public static void main(String[] args) throws Exception {
        boolean forcarReprocessamento = Arrays.asList(args).contains("--processar-pdfs");
        garantirDados(forcarReprocessamento);

        int porta = Integer.parseInt(System.getenv().getOrDefault("PORT", String.valueOf(PORTA_PADRAO)));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", porta), 0);

        server.createContext("/", DashboardServer::rotear);

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("============================================================");
        System.out.println(" Versao_Java - Dashboard Conselho de Classe IFSP");
        System.out.println("------------------------------------------------------------");
        System.out.println(" Dashboard (layout Java) : http://127.0.0.1:" + porta + "/");
        System.out.println(" Integracao PDF          : http://127.0.0.1:" + porta + "/integracao");
        System.out.println(" Todos os dashboards     : http://127.0.0.1:" + porta + "/dashboards");
        System.out.println(" API de dados            : http://127.0.0.1:" + porta + "/api/dados");
        System.out.println(" Dados salvos em         : " + dadosJson());
        System.out.println("============================================================");
    }

    // ------------------------------------------------------------------
    // Roteador
    // ------------------------------------------------------------------

    private static void rotear(HttpExchange ex) throws IOException {
        try {
            String metodo = ex.getRequestMethod();
            String caminho = ex.getRequestURI().getPath();

            if (metodo.equals("GET") || metodo.equals("HEAD")) {
                switch (caminho) {
                    case "/":
                        servirRecurso(ex, "/dashboard.html", "text/html; charset=utf-8");
                        return;
                    case "/integracao":
                        servirRecurso(ex, "/integracao.html", "text/html; charset=utf-8");
                        return;
                    case "/dashboards":
                        servirRecurso(ex, "/dashboards.html", "text/html; charset=utf-8");
                        return;
                    case "/manifest.webmanifest":
                        servirRecurso(ex, "/manifest.webmanifest", "application/manifest+json; charset=utf-8");
                        return;
                    case "/icon.svg":
                        servirRecurso(ex, "/icon.svg", "image/svg+xml; charset=utf-8");
                        return;
                    case "/api/dados":
                        responderDados(ex);
                        return;
                    default:
                        if (caminho.endsWith(".html")) {
                            servirRecurso(ex, caminho, "text/html; charset=utf-8");
                            return;
                        }
                        responderJson(ex, 404, erro("Não encontrado: " + caminho));
                        return;
                }
            }

            if (metodo.equals("POST") && caminho.equals("/api/dados")) {
                apiDadosPost(ex);
                return;
            }
            if (metodo.equals("POST") && caminho.equals("/api/processar-pdfs")) {
                apiProcessarPdfs(ex);
                return;
            }

            responderJson(ex, 405, erro("Método não permitido: " + metodo));
        } catch (Exception e) {
            try {
                responderJson(ex, 500, erro("Erro interno: " + e.getMessage()));
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------
    // Recursos estáticos
    // ------------------------------------------------------------------

    private static void servirRecurso(HttpExchange ex, String recurso, String contentType) throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream(recurso)) {
            if (in == null) {
                responderJson(ex, 404, erro("Recurso não encontrado: " + recurso));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ------------------------------------------------------------------
    // API /api/dados
    // ------------------------------------------------------------------

    private static void responderDados(HttpExchange ex) throws IOException {
        List<Map<String, Object>> alunos;
        try {
            alunos = carregarJson(dadosJson());
        } catch (Exception e) {
            responderJson(ex, 500, erro("JSON inválido: " + e.getMessage()));
            return;
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("arquivo", DADOS_JSON);
        resp.put("alunos", alunos);
        responderJson(ex, 200, resp);
    }

    private static void apiDadosPost(HttpExchange ex) throws IOException {
        JsonNode payload = MAPPER.readTree(ex.getRequestBody());
        JsonNode alunosNode = payload.has("alunos") ? payload.get("alunos") : payload;
        if (!alunosNode.isArray()) {
            responderJson(ex, 400, erro("Payload deve conter uma lista 'alunos'."));
            return;
        }
        try {
            List<Map<String, Object>> alunos = MAPPER.convertValue(alunosNode,
                    new TypeReference<List<Map<String, Object>>>() {});
            salvarJson(alunos, dadosJson());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("arquivo", DADOS_JSON);
            resp.put("alunos", alunos);
            responderJson(ex, 200, resp);
        } catch (Exception e) {
            responderJson(ex, 500, erro("Falha ao salvar JSON: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // API /api/processar-pdfs (multipart/form-data)
    // ------------------------------------------------------------------

    private static void apiProcessarPdfs(HttpExchange ex) throws IOException {
        byte[] corpo = ex.getRequestBody().readAllBytes();
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).contains("multipart/form-data")) {
            responderJson(ex, 400, erro("Envie multipart/form-data."));
            return;
        }
        String boundary = extrairBoundary(contentType);
        if (boundary == null) {
            responderJson(ex, 400, erro("Boundary multipart ausente."));
            return;
        }

        List<ParteMultipart> partes = parseMultipart(corpo, boundary);
        String turma = "";
        boolean substituir = false;
        File napneFile = null;
        List<File> boletins = new ArrayList<>();
        List<File> temporarios = new ArrayList<>();

        try {
            for (ParteMultipart p : partes) {
                if (p.nome == null) {
                    continue;
                }
                switch (p.nome) {
                    case "turma":
                        turma = new String(p.conteudo, StandardCharsets.UTF_8).trim();
                        break;
                    case "substituir":
                        substituir = "1".equals(new String(p.conteudo, StandardCharsets.UTF_8).trim());
                        break;
                    case "napne":
                        if (p.filename != null && !p.filename.isEmpty()) {
                            napneFile = salvarUpload(p, "napne");
                            temporarios.add(napneFile);
                        }
                        break;
                    case "pdfs":
                        if (p.filename != null && !p.filename.isEmpty()) {
                            File f = salvarUpload(p, "boletim" + (boletins.size() + 1));
                            temporarios.add(f);
                            boletins.add(f);
                        }
                        break;
                    default:
                        break;
                }
            }

            if (boletins.isEmpty()) {
                responderJson(ex, 400, erro("Envie ao menos um PDF de boletim."));
                return;
            }

            List<Map<String, Object>> extraidos = new ArrayList<>();
            for (File boletim : boletins) {
                extraidos.addAll(BoletimProcessor.processar(boletim, napneFile, turma, true));
            }

            List<Map<String, Object>> atuais = new ArrayList<>();
            if (!substituir && Files.exists(dadosJson())) {
                atuais = carregarJson(dadosJson());
            }
            List<Map<String, Object>> alunos = mesclarAlunos(atuais, extraidos);
            salvarJson(alunos, dadosJson());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("arquivo", DADOS_JSON);
            resp.put("alunos", alunos);
            responderJson(ex, 200, resp);
        } catch (Exception e) {
            responderJson(ex, 500, erro("Falha ao processar PDFs: " + e.getMessage()));
        } finally {
            for (File f : temporarios) {
                try {
                    Files.deleteIfExists(f.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static File salvarUpload(ParteMultipart parte, String prefixo) throws IOException {
        String nome = secureFilename(parte.filename != null ? parte.filename : prefixo + ".pdf");
        if (!nome.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IOException("Apenas arquivos PDF são aceitos.");
        }
        Files.createDirectories(uploadDir());
        Path destino = uploadDir().resolve(prefixo + "_" + nome);
        Files.write(destino, parte.conteudo);
        return destino.toFile();
    }

    // ------------------------------------------------------------------
    // Seed inicial: processa os 3 PDFs quando dados_alunos.json não existe
    // ------------------------------------------------------------------

    private static void garantirDados(boolean forcar) {
        if (!forcar && Files.exists(dadosJson())) {
            return;
        }
        try {
            List<Map<String, Object>> alunos = new ArrayList<>();
            File napne = localizarPdf(PDF_NAPNE);
            for (String nomeBoletim : PDFs_BOLETINS) {
                File boletim = localizarPdf(nomeBoletim);
                if (boletim == null) {
                    System.out.println("Aviso: PDF não encontrado, ignorando: " + nomeBoletim);
                    continue;
                }
                alunos.addAll(BoletimProcessor.processar(boletim, napne, "", true));
            }
            if (alunos.isEmpty()) {
                System.out.println("Aviso: nenhum aluno extraído dos PDFs.");
                return;
            }
            salvarJson(mesclarAlunos(carregarJson(dadosJson()), alunos), dadosJson());
            System.out.println("[OK] " + alunos.size() + " alunos processados e salvos em " + dadosJson());
        } catch (Exception e) {
            System.err.println("Aviso: falha ao processar PDFs iniciais: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Mesclagem de alunos (mesma regra do app.py / mesclar_alunos)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mesclarAlunos(List<Map<String, Object>> base,
                                                   List<Map<String, Object>> novos) {
        Map<String, Map<String, Object>> porProntuario = new LinkedHashMap<>();
        for (Map<String, Object> a : base) {
            if (a != null) {
                String chave = String.valueOf(a.getOrDefault("prontuario", "")).toUpperCase(Locale.ROOT);
                if (!chave.isEmpty() && !"NULL".equals(chave)) {
                    porProntuario.put(chave, a);
                }
            }
        }
        for (Map<String, Object> aluno : novos) {
            if (aluno == null) {
                continue;
            }
            Object pr = aluno.get("prontuario");
            Object nm = aluno.get("nome");
            String chave = String.valueOf(pr != null && !String.valueOf(pr).isEmpty() ? pr : nm).toUpperCase(Locale.ROOT);
            if (chave.isEmpty() || "NULL".equals(chave)) {
                continue;
            }
            Map<String, Object> antigo = porProntuario.get(chave);
            if (antigo == null) {
                porProntuario.put(chave, aluno);
                continue;
            }

            List<Map<String, Object>> disciplinas = (List<Map<String, Object>>) antigo.get("disciplinas");
            if (disciplinas == null) {
                disciplinas = new ArrayList<>();
                antigo.put("disciplinas", disciplinas);
            }
            Set<String> nomes = new HashSet<>();
            for (Map<String, Object> d : disciplinas) {
                if (d != null && d.get("nome") != null) {
                    nomes.add(String.valueOf(d.get("nome")));
                }
            }
            Object novasDiscs = aluno.get("disciplinas");
            if (novasDiscs instanceof List) {
                for (Object dObj : (List<?>) novasDiscs) {
                    if (dObj instanceof Map) {
                        Map<String, Object> d = (Map<String, Object>) dObj;
                        Object nomeD = d.get("nome");
                        if (nomeD != null && nomes.add(String.valueOf(nomeD))) {
                            disciplinas.add(d);
                        }
                    }
                }
            }
            antigo.put("disciplinas", disciplinas);

            for (String campo : new String[]{"nome", "curso", "frequencia", "num_chamada"}) {
                Object v = aluno.get(campo);
                if (v != null && !(v instanceof String && ((String) v).isEmpty())) {
                    antigo.put(campo, v);
                }
            }
            Object turmaNova = aluno.get("turma");
            if (turmaNova != null && !"Sem turma".equals(turmaNova)) {
                antigo.put("turma", turmaNova);
            }
            if (vazio(antigo.get("deliberacao")) && !vazio(aluno.get("deliberacao"))) {
                antigo.put("deliberacao", aluno.get("deliberacao"));
            }
            List<String> acoes = (List<String>) antigo.get("acoesNapne");
            if (acoes == null) {
                acoes = new ArrayList<>();
                antigo.put("acoesNapne", acoes);
            }
            Object novasAcoes = aluno.get("acoesNapne");
            if (novasAcoes instanceof List) {
                for (Object ac : (List<?>) novasAcoes) {
                    if (ac != null && !acoes.contains(String.valueOf(ac))) {
                        acoes.add(String.valueOf(ac));
                    }
                }
            }
            if (Boolean.TRUE.equals(aluno.get("napne"))) {
                antigo.put("napne", true);
                if (!vazio(aluno.get("pneInfo"))) {
                    antigo.put("pneInfo", aluno.get("pneInfo"));
                }
            }
            if (vazio(antigo.get("foto")) && !vazio(aluno.get("foto"))) {
                antigo.put("foto", aluno.get("foto"));
            }
        }

        List<Map<String, Object>> resultado = new ArrayList<>(porProntuario.values());
        resultado.sort((a, b) -> {
            double na = numero(a.get("num_chamada"));
            double nb = numero(b.get("num_chamada"));
            if (na != nb) {
                return Double.compare(na, nb);
            }
            return String.valueOf(a.getOrDefault("nome", "")).compareTo(String.valueOf(b.getOrDefault("nome", "")));
        });
        return resultado;
    }

    private static boolean vazio(Object v) {
        return v == null || (v instanceof String && ((String) v).isEmpty());
    }

    private static double numero(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return v == null ? 0 : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> carregarJson(Path arquivo) throws IOException {
        if (!Files.exists(arquivo)) {
            return new ArrayList<>();
        }
        JsonNode raiz = MAPPER.readTree(arquivo.toFile());
        JsonNode lista = (raiz.isObject() && raiz.has("alunos")) ? raiz.get("alunos") : raiz;
        if (!lista.isArray()) {
            return new ArrayList<>();
        }
        return MAPPER.convertValue(lista, new TypeReference<List<Map<String, Object>>>() {});
    }

    static void salvarJson(List<Map<String, Object>> alunos, Path destino) throws IOException {
        Files.createDirectories(destino.toAbsolutePath().getParent());
        DefaultIndenter indent = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentArraysWith(indent);
        pp.indentObjectsWith(indent);
        // Jackson já grava UTF-8 sem BOM e sem escapar acentos por padrão
        byte[] bytes = MAPPER.writer(pp).writeValueAsBytes(alunos);
        byte[] comNewline = Arrays.copyOf(bytes, bytes.length + 1);
        comNewline[bytes.length] = '\n';
        Files.write(destino, comNewline);
    }

    private static Map<String, Object> erro(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("erro", msg);
        return m;
    }

    private static void responderJson(HttpExchange ex, int status, Object corpo) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(corpo);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ------------------------------------------------------------------
    // Multipart/form-data (parser mínimo)
    // ------------------------------------------------------------------

    private static class ParteMultipart {
        String nome;
        String filename;
        byte[] conteudo;
    }

    private static String extrairBoundary(String contentType) {
        for (String parte : contentType.split(";")) {
            parte = parte.trim();
            if (parte.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = parte.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    private static int indexOf(byte[] dados, byte[] padrao, int aPartir) {
        outer:
        for (int i = aPartir; i <= dados.length - padrao.length; i++) {
            for (int j = 0; j < padrao.length; j++) {
                if (dados[i + j] != padrao[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static List<ParteMultipart> parseMultipart(byte[] corpo, String boundary) {
        List<ParteMultipart> partes = new ArrayList<>();
        byte[] marcador = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] fimCabecalho = {'\r', '\n', '\r', '\n'};
        byte[] crlf = {'\r', '\n'};

        int pos = indexOf(corpo, marcador, 0);
        while (pos >= 0) {
            pos += marcador.length;
            // "--" final?
            if (pos + 1 < corpo.length && corpo[pos] == '-' && corpo[pos + 1] == '-') {
                break;
            }
            // pula CRLF após o boundary
            if (pos + 1 < corpo.length && corpo[pos] == '\r' && corpo[pos + 1] == '\n') {
                pos += 2;
            }
            int fimHeaders = indexOf(corpo, fimCabecalho, pos);
            if (fimHeaders < 0) {
                break;
            }
            String headers = new String(corpo, pos, fimHeaders - pos, StandardCharsets.ISO_8859_1);
            int inicioConteudo = fimHeaders + fimCabecalho.length;

            // conteúdo vai até CRLF + próximo boundary
            byte[] fechamento = new byte[crlf.length + marcador.length];
            System.arraycopy(crlf, 0, fechamento, 0, crlf.length);
            System.arraycopy(marcador, 0, fechamento, crlf.length, marcador.length);
            int fimConteudo = indexOf(corpo, fechamento, inicioConteudo);
            if (fimConteudo < 0) {
                break;
            }

            ParteMultipart parte = new ParteMultipart();
            parte.conteudo = Arrays.copyOfRange(corpo, inicioConteudo, fimConteudo);

            // Content-Disposition
            for (String linha : headers.split("\r\n")) {
                if (linha.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) {
                    String disp = linha.substring("content-disposition:".length()).trim();
                    parte.nome = extrairParam(disp, "name");
                    String fn = extrairParam(disp, "filename");
                    if (fn == null) {
                        fn = extrairParam(disp, "filename*");
                    }
                    parte.filename = fn;
                }
            }
            partes.add(parte);
            pos = fimConteudo + fechamento.length;
        }
        return partes;
    }

    private static String extrairParam(String disposition, String chave) {
        // name="valor" ou name=valor
        Pattern p = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(chave) + "\\*?=(?:\"([^\"]*)\"|([^;\\s]*))",
                Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(disposition);
        if (m.find()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            // filename*=UTF-8''nome -> decodifica
            if (v != null && v.contains("''")) {
                try {
                    String[] pedacos = v.split("''", 2);
                    v = new String(java.net.URLDecoder.decode(pedacos.length > 1 ? pedacos[1] : pedacos[0],
                            StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
                } catch (Exception ignored) {
                }
            }
            return v;
        }
        return null;
    }

    /** Equivalente ao secure_filename do Werkzeug: remove diretórios e caracteres inseguros. */
    private static String secureFilename(String nome) {
        if (nome == null) {
            return "arquivo.pdf";
        }
        nome = nome.replace('\\', '/');
        int barra = nome.lastIndexOf('/');
        if (barra >= 0) {
            nome = nome.substring(barra + 1);
        }
        nome = nome.replaceAll("[^A-Za-z0-9._()\\- ]", "_");
        nome = nome.replaceAll("\\s+", "_");
        if (nome.isEmpty() || nome.equals(".") || nome.equals("..")) {
            nome = "arquivo.pdf";
        }
        return nome;
    }
}
