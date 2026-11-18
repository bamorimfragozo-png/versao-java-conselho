package br.sp.readPDF;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.text.PDFTextStripper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import java.awt.Paint;
import java.awt.geom.Point2D;

/**
 * Processador de Boletins e Relatórios NAPNE - IFSP Boituva.
 * Port das regras de negócio do importio.py (Versao_Python) para Java + PDFBox 3.
 */
@Command(name = "BoletimProcessor", mixinStandardHelpOptions = true, version = "1.0",
        description = "Processador de Boletins e Relatórios NAPNE em Java - IFSP Boituva.")
public class BoletimProcessor implements Callable<Integer> {

    @Option(names = { "-b", "--boletim" }, required = true, description = "Caminho do PDF de boletins.")
    private File arquivoBoletim;

    @Option(names = { "-n", "--napne" }, description = "Caminho do PDF do NAPNE (opcional).")
    private File arquivoNapne;

    @Option(names = { "-t", "--turma" }, defaultValue = "", description = "Nome da turma (fallback; a turma é lida do PDF).")
    private String nomeTurma;

    @Option(names = { "-o", "--output" }, defaultValue = "dados_alunos.json", description = "Nome do JSON gerado.")
    private String arquivoSaida;

    @Option(names = { "--sem-fotos" }, description = "Não extrai as fotos dos alunos.")
    private boolean semFotos;

    // Palavras-chave (siglas) para identificar matérias técnicas
    private static final List<String> TECNICAS = Arrays.asList(
            "ILPR", "MAIN", "ININ", "LDPR", "RDCO", "LPWE", "SOPE", "INSO", "IPRE",
            "BDDA", "PSCO", "PRIN", "CNVI", "SDRE", "ASRE", "RSFI", "GCLI", "ELET",
            "DCAD", "CAUT", "PROG", "PCOE", "EDIG", "PRI1", "ELIN", "CISUT", "INTI",
            "MAPI", "CNCM", "CLPR", "REPI", "HIEP", "MIMP", "PRI2");

    // Algumas versões do extrator "colam" as palavras na quebra de linha do nome
    private static final Map<String, String> CORRECOES_NOME = new HashMap<>();
    static {
        CORRECOES_NOME.put("CONTEÚDOPARA", "CONTEÚDO PARA");
        CORRECOES_NOME.put("DESISTEMAS", "DE SISTEMAS");
        CORRECOES_NOME.put("DESERVIÇOS", "DE SERVIÇOS");
        CORRECOES_NOME.put("EVIRTUALIZAÇÃO", "E VIRTUALIZAÇÃO");
        CORRECOES_NOME.put("INTEGRADOR(TURMA", "INTEGRADOR (TURMA");
    }

    // Nota = "10,00" ou "10.00" ou "-" (sem nota). Falta = inteiro.
    private static final String NOTA = "\\d{1,2}[.,]\\d{2}|-";
    private static final String FALTA = "\\d+";

    // INT.xxxxx (SIGLA) - NOME  CH  aulas  T.aulas  T.faltas  %freq
    //   <situação>  N1 F1  N2 F2  N3 F3  N4 F4  MD  NAF(N) NAF(F)  MFD
    private static final Pattern PADRAO_DISCIPLINA = Pattern.compile(
            "(?<nome>INT\\.\\d{5}\\s*\\((?<sigla>[A-Z0-9]+)\\)\\s*-\\s*.+?)\\s+"
                    + "(?<ch>\\d+[.,]\\d{2})\\s+(?<aulas>\\d+)\\s+(?<taulas>\\d+)\\s+"
                    + "(?<tfaltas>\\d+)\\s+(?<freq>\\d+(?:[.,]\\d+)?)%\\s+"
                    + "(?<situacao>[^\\d\\-]+?)\\s*"
                    + "(?<n1>" + NOTA + ")\\s+(?<f1>" + FALTA + ")\\s+"
                    + "(?<n2>" + NOTA + ")\\s+(?<f2>" + FALTA + ")\\s+"
                    + "(?<n3>" + NOTA + ")\\s+(?<f3>" + FALTA + ")\\s+"
                    + "(?<n4>" + NOTA + ")\\s+(?<f4>" + FALTA + ")");

    private static final Pattern PAT_PRONTUARIO = Pattern.compile("BT\\d{7}");
    private static final Pattern PAT_TURMA = Pattern.compile("Turma\\s*:\\s*([A-Za-z0-9._-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TURMA2 = Pattern.compile("202\\d+\\.\\d+\\.[A-Z0-9.]+");
    private static final Pattern PAT_FREQ = Pattern.compile("Frequência\\s*:\\s*\\|?\\s*(\\d+[.,]?\\d*)\\s*%", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_NOME_ALUNO = Pattern.compile("Aluno\\(a\\):\\s*([^\\n|]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_NOME_ALUNO2 = Pattern.compile("BOLETIM DE NOTAS INDIVIDUAL\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_CURSO = Pattern.compile("Curso\\s*:\\s*([^\\n]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_MATRICULA_INLINE = Pattern.compile("Matrícula:.*", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_PNE = Pattern.compile(
            "Portador\\(a\\)\\s+de\\s+Necessidades\\s+Especiais\\s*:?\\s*(Sim|Não)"
                    + "(?:\\s+Tipo de Necessidade Especial\\s*:?\\s*-?\\s*([A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9\\-/]{0,15}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TRANSTORNO = Pattern.compile(
            "Portador\\(a\\)\\s+de\\s+Transtorno\\s*:?\\s*(Sim|Não)"
                    + "(?:\\s+Tipo de Transtorno\\s*:?\\s*-?\\s*([A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9\\-/]{0,15}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_SUPERDOTACAO = Pattern.compile(
            "Portador\\(a\\)\\s+de\\s+Superdota\\S{0,3}o\\s*:?\\s*(Sim|Não)"
                    + "(?:\\s+Superdota\\S{0,3}o\\s*:?\\s*-?\\s*([A-Za-zÀ-ÿ0-9][A-Za-zÀ-ÿ0-9\\-/]{0,15}))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_OBS = Pattern.compile("Observa..es Gerais\\s*(.*?)(?=Dados Acad|Matr.cula|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_URL = Pattern.compile("https?://\\S+");
    private static final Pattern PAT_LINHA_SUAP = Pattern.compile("\\d{2}/\\d{2}/\\d{4},?\\s*\\d{1,2}:\\d{2}.*?- SUAP.*?(?=\\d/\\d|$)");
    private static final Pattern PAT_FRACAO = Pattern.compile("\\d+/\\d+");

    private static final Pattern PAT_PNE_BOLETIM = Pattern.compile(
            "Portador\\(a\\)\\s+de\\s+Necessidades\\s+Especiais\\s*:?\\s*(Sim|Não)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_PNE_TIPO = Pattern.compile(
            "Tipo\\s+de\\s+Necessidade\\s+Especial\\s*:?\\s*(.*?)(?=Portador|\\bTranstorno\\b|\\bSuperdotação\\b|Disciplina|\\Z)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TRANSTORNO_BOLETIM = Pattern.compile(
            "Portador\\(a\\)\\s+de\\s+Transtorno\\s*:?\\s*(Sim|Não)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_TRANSTORNO_TIPO = Pattern.compile(
            "Tipo\\s+de\\s+Transtorno\\s*:?\\s*(.*?)(?=Portador|\\bSuperdotação\\b|Disciplina|\\Z)",
            Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) {
        int exitCode = new CommandLine(new BoletimProcessor()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (!arquivoBoletim.exists()) {
            System.err.println("Erro Crítico: O PDF de boletins não existe.");
            return 1;
        }
        if (arquivoNapne != null && !arquivoNapne.exists()) {
            System.err.println("Erro Crítico: O PDF do NAPNE não existe.");
            return 1;
        }
        try {
            List<Map<String, Object>> alunos = processar(
                    arquivoBoletim,
                    arquivoNapne != null ? arquivoNapne : null,
                    nomeTurma,
                    !semFotos);
            salvarJson(alunos, new File(arquivoSaida));
            System.out.println("\n[SUCESSO] Processamento concluído: " + alunos.size() + " alunos mapeados.");
            System.out.println("Estrutura gerada com sucesso em: '" + arquivoSaida + "'");
            return 0;
        } catch (Exception e) {
            System.err.println("Erro durante o processamento: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /**
     * Processa um PDF de boletins (e opcionalmente um PDF NAPNE) e devolve a lista
     * de alunos no mesmo formato do dados_alunos.json da Versao_Python.
     */
    public static List<Map<String, Object>> processar(File caminhoBoletim, File caminhoNapne,
                                                       String nomeTurma, boolean extrairFotos) throws IOException {
        Map<String, String> dadosNapne = carregarDadosNapnePdf(caminhoNapne);
        Map<String, Map<String, Object>> alunosMapeados = new LinkedHashMap<>();
        int numeroChamada = 1;

        System.out.println("Lendo PDF de boletins: " + caminhoBoletim.getName() + "...");
        try (PDDocument docBoletim = Loader.loadPDF(caminhoBoletim)) {
            List<GrupoAluno> grupos = agruparPaginasPorAluno(docBoletim, extrairFotos);

            for (GrupoAluno grupo : grupos) {
                String bloco = grupo.texto;
                if (!bloco.contains("Disciplina") && !bloco.contains("TÉCNICO")) {
                    continue;
                }

                String blocoLimpo = bloco.replaceAll("\\s+", " ");
                String nomeAluno = extrairNomeAluno(bloco);
                String matriculaAluno = extrairMatricula(bloco);
                String turmaAluno = extrairTurma(blocoLimpo);
                String serieAluno = turmaAluno;
                String cursoAluno = extrairCurso(bloco);
                double freqGlobal = extrairFrequenciaGlobal(blocoLimpo);

                if (nomeAluno.isEmpty() || nomeAluno.equals("Não Identificado") || nomeAluno.startsWith("BT30")) {
                    continue;
                }

                String prontuarioChave = !matriculaAluno.isEmpty() ? matriculaAluno : "SEM_MATRICULA_" + numeroChamada;

                if (!alunosMapeados.containsKey(prontuarioChave)) {
                    List<String> pneTexto = new ArrayList<>();

                    Matcher mPne = PAT_PNE_BOLETIM.matcher(blocoLimpo);
                    if (mPne.find() && mPne.group(1).equalsIgnoreCase("Sim")) {
                        Matcher mTip = PAT_PNE_TIPO.matcher(blocoLimpo);
                        String tipo = mTip.find() ? mTip.group(1).trim() : "-";
                        pneTexto.add("PNE: " + tipo);
                    }

                    Matcher mTrans = PAT_TRANSTORNO_BOLETIM.matcher(blocoLimpo);
                    if (mTrans.find() && mTrans.group(1).equalsIgnoreCase("Sim")) {
                        Matcher mTip = PAT_TRANSTORNO_TIPO.matcher(blocoLimpo);
                        String tipo = mTip.find() ? mTip.group(1).trim() : "-";
                        pneTexto.add("Transtorno: " + tipo);
                    }

                    String chaveBuscaNapne = prontuarioChave.toUpperCase(Locale.ROOT);
                    boolean possuiNapneExterno = dadosNapne.containsKey(chaveBuscaNapne);
                    if (possuiNapneExterno) {
                        pneTexto.add("Laudo NAPNE: " + dadosNapne.get(chaveBuscaNapne));
                    }

                    String infoNapne = pneTexto.isEmpty()
                            ? "Nenhum registro de PNE/Transtorno/Superdotação."
                            : String.join(" | ", pneTexto);

                    String cursoFinal = !cursoAluno.isEmpty() ? cursoAluno
                            : (!serieAluno.isEmpty() ? serieAluno : "Técnico Integrado");
                    String turmaFinal = !turmaAluno.isEmpty() ? turmaAluno
                            : (!nomeTurma.isEmpty() ? nomeTurma : "Sem turma");

                    Map<String, Object> aluno = new LinkedHashMap<>();
                    aluno.put("num_chamada", numeroChamada);
                    aluno.put("prontuario", prontuarioChave);
                    aluno.put("nome", nomeAluno);
                    aluno.put("curso", cursoFinal);
                    aluno.put("turma", turmaFinal);
                    aluno.put("frequencia", freqGlobal);
                    aluno.put("napne", !pneTexto.isEmpty() || possuiNapneExterno);
                    aluno.put("pneInfo", infoNapne);
                    aluno.put("deliberacao", "");
                    aluno.put("foto", grupo.foto);
                    aluno.put("disciplinas", new ArrayList<Map<String, Object>>());

                    alunosMapeados.put(prontuarioChave, aluno);
                    numeroChamada++;
                }

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> listaDisciplinasAluno =
                        (List<Map<String, Object>>) alunosMapeados.get(prontuarioChave).get("disciplinas");
                Set<String> existentes = new HashSet<>();
                for (Map<String, Object> d : listaDisciplinasAluno) {
                    existentes.add(String.valueOf(d.get("nome")));
                }
                for (Map<String, Object> disc : extrairDisciplinas(bloco)) {
                    if (!existentes.contains(String.valueOf(disc.get("nome")))) {
                        listaDisciplinasAluno.add(disc);
                        existentes.add(String.valueOf(disc.get("nome")));
                    }
                }
            }
        }
        return new ArrayList<>(alunosMapeados.values());
    }

    /** Grava a lista de alunos em JSON UTF-8 sem BOM (mesmo formato do app.py). */
    public static void salvarJson(List<Map<String, Object>> alunos, File destino) throws IOException {
        File pai = destino.getAbsoluteFile().getParentFile();
        if (pai != null) {
            pai.mkdirs();
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter indent = new DefaultIndenter("    ", "\n");
        pp.indentArraysWith(indent);
        pp.indentObjectsWith(indent);
        mapper.writer(pp).writeValue(destino, alunos);
    }

    // ------------------------------------------------------------------
    // PDF do NAPNE / apontamentos
    // ------------------------------------------------------------------

    private static Map<String, String> carregarDadosNapnePdf(File caminhoNapnePdf) {
        Map<String, String> napneDict = new HashMap<>();
        if (caminhoNapnePdf == null || !caminhoNapnePdf.exists()) {
            System.out.println("Aviso: Arquivo do NAPNE não informado. Prosseguindo apenas com dados do PDF de boletins.");
            return napneDict;
        }
        try {
            System.out.println("Lendo PDF do NAPNE: " + caminhoNapnePdf.getName() + "...");
            String textoCompleto;
            try (PDDocument doc = Loader.loadPDF(caminhoNapnePdf)) {
                PDFTextStripper stripper = new PDFTextStripper();
                textoCompleto = stripper.getText(doc);
            }
            String textoLimpo = textoCompleto.replaceAll("\\s+", " ");

            Matcher m = Pattern.compile("BT\\d{7}").matcher(textoLimpo);
            // primeira ocorrência de cada prontuário, na ordem em que aparecem
            Map<String, Integer> posPrimeira = new LinkedHashMap<>();
            while (m.find()) {
                posPrimeira.putIfAbsent(m.group().toUpperCase(Locale.ROOT), m.start());
            }
            if (posPrimeira.isEmpty()) {
                return napneDict;
            }

            List<String> prontuarios = new ArrayList<>(posPrimeira.keySet());
            for (int i = 0; i < prontuarios.size(); i++) {
                String prontuario = prontuarios.get(i);
                int ini = posPrimeira.get(prontuario);
                int fim = (i + 1 < prontuarios.size()) ? posPrimeira.get(prontuarios.get(i + 1)) : textoLimpo.length();
                String bloco = textoLimpo.substring(ini, fim);
                String conteudo = extrairInfoNapneBloco(bloco);
                napneDict.put(prontuario, conteudo.isEmpty() ? "Aluno registrado no NAPNE" : conteudo);
            }
        } catch (Exception e) {
            System.err.println("Erro ao processar o PDF do NAPNE: " + e.getMessage());
        }
        return napneDict;
    }

    private static String extrairInfoNapneBloco(String bloco) {
        List<String> partes = new ArrayList<>();

        Matcher mPne = PAT_PNE.matcher(bloco);
        if (mPne.find()) {
            String tipo = mPne.group(2) != null ? mPne.group(2).trim() : "";
            tipo = tipo.replaceAll("^[-\\s]+|[-\\s]+$", "");
            if (mPne.group(1).trim().equalsIgnoreCase("sim")) {
                partes.add("Necessidades Especiais: Sim" + ((!tipo.isEmpty() && !tipo.equals("-")) ? " (Tipo " + tipo + ")" : ""));
            } else {
                partes.add("Necessidades Especiais: Não");
            }
        }

        Matcher mTrans = PAT_TRANSTORNO.matcher(bloco);
        if (mTrans.find()) {
            String tipo = mTrans.group(2) != null ? mTrans.group(2).trim() : "";
            tipo = tipo.replaceAll("^[-\\s]+|[-\\s]+$", "");
            if (mTrans.group(1).trim().equalsIgnoreCase("sim")) {
                partes.add("Transtorno: Sim" + ((!tipo.isEmpty() && !tipo.equals("-")) ? " (Tipo " + tipo + ")" : ""));
            } else {
                partes.add("Transtorno: Não");
            }
        }

        Matcher mSup = PAT_SUPERDOTACAO.matcher(bloco);
        if (mSup.find()) {
            String tipo = mSup.group(2) != null ? mSup.group(2).trim() : "";
            tipo = tipo.replaceAll("^[-\\s]+|[-\\s]+$", "");
            if (mSup.group(1).trim().equalsIgnoreCase("sim")) {
                partes.add("Superdotação: Sim" + ((!tipo.isEmpty() && !tipo.equals("-")) ? " (" + tipo + ")" : ""));
            } else {
                partes.add("Superdotação: Não");
            }
        }

        Matcher mObs = PAT_OBS.matcher(bloco);
        if (mObs.find()) {
            String obs = mObs.group(1).replaceAll("\\s+", " ").replaceAll("^[:-|\\s]+|[:-|\\s]+$", "");
            if (!obs.isEmpty() && !obs.toLowerCase(Locale.ROOT).contains("nenhuma")) {
                partes.add("Observações: " + obs.substring(0, Math.min(240, obs.length())));
            }
        }

        if (!partes.isEmpty()) {
            return String.join(" | ", partes);
        }

        String limpo = PAT_URL.matcher(bloco).replaceAll(" ");
        limpo = PAT_LINHA_SUAP.matcher(limpo).replaceAll(" ");
        StringBuilder tokens = new StringBuilder();
        for (String t : limpo.trim().split("\\s+")) {
            if (t.isEmpty() || t.contains("=") || t.startsWith("/") || PAT_FRACAO.matcher(t).matches()) {
                continue;
            }
            if (tokens.length() > 0) tokens.append(' ');
            tokens.append(t);
        }
        String res = tokens.toString().trim();
        return res.substring(0, Math.min(400, res.length()));
    }

    // ------------------------------------------------------------------
    // Agrupamento de páginas por aluno + fotos
    // ------------------------------------------------------------------

    private static class ImgInfo {
        final PDImage imagem;
        final long area;
        ImgInfo(PDImage imagem, long area) {
            this.imagem = imagem;
            this.area = area;
        }
    }

    /**
     * Motor de conteúdo que coleta apenas as imagens efetivamente desenhadas na
     * página (operador Do e imagens inline BI/EI), como o pypdf faz.
     */
    private static class ColetorImagens extends PDFGraphicsStreamEngine {
        final Map<Object, ImgInfo> imagens = new LinkedHashMap<>();

        ColetorImagens(PDPage pagina) {
            super(pagina);
        }

        @Override
        public void drawImage(PDImage imagem) {
            try {
                if (imagem == null || imagem.isEmpty()) {
                    return;
                }
                long area = (long) imagem.getWidth() * imagem.getHeight();
                if (area <= 0) {
                    return;
                }
                Object chave = chaveImagem(imagem);
                imagens.putIfAbsent(chave, new ImgInfo(imagem, area));
            } catch (Exception ignored) {
            }
        }

        private Object chaveImagem(PDImage img) {
            if (img instanceof PDInlineImage) {
                // imagens inline não têm objeto COS compartilhado: identifica pelo conteúdo bruto
                try {
                    byte[] dados = ((PDInlineImage) img).getData();
                    return "inline:" + img.getWidth() + "x" + img.getHeight() + ":"
                            + dados.length + ":" + Arrays.hashCode(dados);
                } catch (Exception e) {
                    return img;
                }
            }
            COSBase cos = img.getCOSObject();
            return cos != null ? cos : img;
        }

        // ---- stubs dos métodos abstratos de PDFGraphicsStreamEngine ----
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { }
        @Override public void clip(int windingRule) { }
        @Override public void moveTo(float x, float y) { }
        @Override public void lineTo(float x, float y) { }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { }
        @Override public Point2D getCurrentPoint() { return new Point2D.Float(); }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { }
        @Override public void fillPath(int windingRule) { }
        @Override public void fillAndStrokePath(int windingRule) { }
        @Override public void shadingFill(COSName shadingName) { }
    }

    private static class GrupoAluno {
        String texto = "";
        // Imagens da primeira página do boletim
        final Map<Object, ImgInfo> imgsInicio = new LinkedHashMap<>();
        // Imagens que também aparecem nas páginas de continuação (ex.: logo)
        final Set<Object> imgsOutras = new HashSet<>();
        String foto;
    }

    private static List<GrupoAluno> agruparPaginasPorAluno(PDDocument doc, boolean extrairFotos) throws IOException {
        List<GrupoAluno> grupos = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();

        for (int i = 1; i <= doc.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String textoPagina = stripper.getText(doc);
            boolean eInicio = textoPagina.contains("BOLETIM DE NOTAS INDIVIDUAL");
            Map<Object, ImgInfo> imagens = extrairFotos ? imagensDaPagina(doc.getPage(i - 1)) : Collections.emptyMap();

            if (eInicio || grupos.isEmpty()) {
                GrupoAluno g = new GrupoAluno();
                g.texto = textoPagina + "\n";
                g.imgsInicio.putAll(imagens);
                grupos.add(g);
            } else {
                GrupoAluno g = grupos.get(grupos.size() - 1);
                g.texto += textoPagina + "\n";
                g.imgsOutras.addAll(imagens.keySet());
            }
        }

        // foto = maior imagem da 1ª página que NÃO se repete nas páginas seguintes
        for (GrupoAluno g : grupos) {
            ImgInfo melhor = null;
            for (Map.Entry<Object, ImgInfo> e : g.imgsInicio.entrySet()) {
                if (g.imgsOutras.contains(e.getKey())) {
                    continue; // logo institucional repetido
                }
                if (melhor == null || e.getValue().area > melhor.area) {
                    melhor = e.getValue();
                }
            }
            g.foto = melhor != null ? codificarImagem(melhor.imagem) : null;
        }
        return grupos;
    }

    /** Imagens efetivamente desenhadas na página (XObject via Do + inline BI/EI). */
    private static Map<Object, ImgInfo> imagensDaPagina(PDPage page) {
        try {
            ColetorImagens coletor = new ColetorImagens(page);
            coletor.processPage(page);
            return coletor.imagens;
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    private static String codificarImagem(PDImage image) {
        try {
            String sufixo = image.getSuffix();
            if (sufixo == null || sufixo.isEmpty()) {
                sufixo = "png";
            }
            String formato = sufixo.equalsIgnoreCase("jpg") || sufixo.equalsIgnoreCase("jpeg") ? "jpg" : sufixo.toLowerCase(Locale.ROOT);
            if (!Arrays.asList("jpg", "png", "gif", "bmp").contains(formato)) {
                formato = "png";
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image.getImage(), formato, baos);
            String mime = formato.equals("jpg") ? "image/jpeg" : "image/" + formato;
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Extração de campos do aluno
    // ------------------------------------------------------------------

    private static List<Map<String, Object>> extrairDisciplinas(String bloco) {
        String texto = bloco.replaceAll("\\s+", " ");
        List<Map<String, Object>> disciplinas = new ArrayList<>();
        Matcher matcher = PADRAO_DISCIPLINA.matcher(texto);
        while (matcher.find()) {
            String nomeMateria = limparNomeMateria(matcher.group("nome"));
            String sigla = matcher.group("sigla").toUpperCase(Locale.ROOT);
            boolean isTecnico = TECNICAS.stream().anyMatch(sigla::contains);

            Map<String, Object> disc = new LinkedHashMap<>();
            disc.put("nome", nomeMateria);
            disc.put("b1", tratarNota(matcher.group("n1")));
            disc.put("b2", tratarNota(matcher.group("n2")));
            disc.put("b3", tratarNota(matcher.group("n3")));
            disc.put("b4", tratarNota(matcher.group("n4")));
            disc.put("nucleo", isTecnico ? "Técnico" : "Comum");
            disc.put("faltas", Integer.parseInt(matcher.group("tfaltas")));
            disc.put("freq", Double.parseDouble(matcher.group("freq").replace(",", ".")));
            disciplinas.add(disc);
        }
        return disciplinas;
    }

    private static String limparNomeMateria(String nome) {
        for (Map.Entry<String, String> entry : CORRECOES_NOME.entrySet()) {
            nome = nome.replace(entry.getKey(), entry.getValue());
        }
        return nome.trim();
    }

    private static Double tratarNota(String v) {
        if (v == null) {
            return null;
        }
        v = v.trim();
        if (v.isEmpty() || v.equals("-")) {
            return null;
        }
        try {
            return Double.parseDouble(v.replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extrairNomeAluno(String bloco) {
        Matcher m = PAT_NOME_ALUNO.matcher(bloco);
        if (m.find()) {
            return PAT_MATRICULA_INLINE.matcher(m.group(1)).replaceAll("").trim();
        }
        m = PAT_NOME_ALUNO2.matcher(bloco);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extrairMatricula(String bloco) {
        Matcher m = PAT_PRONTUARIO.matcher(bloco);
        return m.find() ? m.group().trim() : "";
    }

    private static String extrairTurma(String blocoLimpo) {
        Matcher m = PAT_TURMA.matcher(blocoLimpo);
        if (m.find()) {
            return m.group(1).trim();
        }
        m = PAT_TURMA2.matcher(blocoLimpo);
        return m.find() ? m.group().trim() : "";
    }

    private static String extrairCurso(String bloco) {
        Matcher m = PAT_CURSO.matcher(bloco);
        return m.find() ? m.group(1).trim() : "";
    }

    private static double extrairFrequenciaGlobal(String blocoLimpo) {
        Matcher m = PAT_FREQ.matcher(blocoLimpo);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group(1).replace(",", "."));
            } catch (NumberFormatException ignored) {
            }
        }
        return 100.0;
    }
}
