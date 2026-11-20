package br.sp.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Sincroniza dados_alunos.json com o repositório no GitHub, que passa a ser
 * a fonte única da verdade. O GitHub nunca é acessado diretamente pelo
 * navegador: apenas o servidor fala com a API, usando um token secreto.
 *
 * Configuração por variáveis de ambiente:
 *   GITHUB_TOKEN       (obrigatório) Personal Access Token com permissão
 *                      "Contents: Read and write" no repositório.
 *   GITHUB_REPO        (obrigatório) repositório no formato "owner/nome"
 *                      (ex.: "amorin/versao-java-conselho").
 *   GITHUB_BRANCH      (opcional)    branch que contém o JSON (padrão: "main").
 *   GITHUB_DATA_PATH   (opcional)    caminho do arquivo no repo
 *                      (padrão: "dados_alunos.json").
 *
 * Se GITHUB_TOKEN ou GITHUB_REPO não estiverem definidos, todos os métodos
 * viram no-op e o sistema continua usando apenas o arquivo local
 * (comportamento idêntico ao anterior).
 *
 * Nenhum método lança exceção: falhas de rede/API apenas geram log e o
 * dashboard continua funcionando com o arquivo local.
 */
final class GitHubSync {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private GitHubSync() {
    }

    private static String token() {
        return System.getenv("GITHUB_TOKEN");
    }

    private static String repo() {
        return System.getenv("GITHUB_REPO");
    }

    private static String branch() {
        String b = System.getenv("GITHUB_BRANCH");
        return (b == null || b.isBlank()) ? "main" : b.trim();
    }

    private static String dataPath() {
        String p = System.getenv("GITHUB_DATA_PATH");
        return (p == null || p.isBlank()) ? "dados_alunos.json" : p.trim();
    }

    static boolean configurado() {
        String t = token();
        String r = repo();
        return t != null && !t.isBlank() && r != null && !r.isBlank();
    }

    /**
     * Baixa o JSON do GitHub e grava no arquivo local.
     * Retorna true se o arquivo existia no repositório remoto.
     */
    static boolean pull(Path destino) {
        if (!configurado()) {
            return false;
        }
        try {
            HttpResponse<String> resp = enviar("GET", urlConteudo(), null);
            if (resp.statusCode() == 404) {
                return false;
            }
            if (resp.statusCode() != 200) {
                log("pull", "HTTP " + resp.statusCode());
                return false;
            }
            JsonNode no = MAPPER.readTree(resp.body());
            String conteudo = no.path("content").asText("");
            if (conteudo.isEmpty()) {
                return false;
            }
            byte[] bytes = Base64.getDecoder().decode(conteudo.replace("\n", ""));
            if (Files.exists(destino)
                    && java.util.Arrays.equals(Files.readAllBytes(destino), bytes)) {
                return true;
            }
            Files.createDirectories(destino.toAbsolutePath().getParent());
            Files.write(destino, bytes);
            System.out.println("[GitHubSync] dados baixados do GitHub (" + bytes.length + " bytes).");
            return true;
        } catch (Exception e) {
            log("pull", e.getMessage());
            return false;
        }
    }

    /**
     * Envia o arquivo local para o GitHub como um novo commit.
     * No-op se o conteúdo remoto já for idêntico.
     */
    static void push(Path origem, String mensagem) {
        if (!configurado() || !Files.exists(origem)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(origem);

            // Se o remoto já tem exatamente este conteúdo, não cria commit vazio.
            InfoRemoto remoto = infoRemoto();
            String shaAtual = remoto != null ? remoto.sha : null;
            if (remoto != null && remoto.conteudo != null
                    && java.util.Arrays.equals(remoto.conteudo, bytes)) {
                return;
            }

            ObjectNode corpo = MAPPER.createObjectNode();
            corpo.put("message", mensagem);
            corpo.put("content", Base64.getEncoder().encodeToString(bytes));
            corpo.put("branch", branch());
            if (shaAtual != null) {
                corpo.put("sha", shaAtual);
            }
            HttpResponse<String> resp = enviar("PUT", urlConteudo(), corpo.toString());
            if (resp.statusCode() >= 300) {
                log("push", "HTTP " + resp.statusCode() + " " + resp.body());
            } else {
                System.out.println("[GitHubSync] dados enviados ao GitHub.");
            }
        } catch (Exception e) {
            log("push", e.getMessage());
        }
    }

    private static final class InfoRemoto {
        String sha;
        byte[] conteudo;
    }

    /** Busca sha e conteúdo do arquivo remoto em uma única chamada. Null se não existir/falhar. */
    private static InfoRemoto infoRemoto() {
        try {
            HttpResponse<String> resp = enviar("GET", urlConteudo(), null);
            if (resp.statusCode() != 200) {
                return null;
            }
            JsonNode no = MAPPER.readTree(resp.body());
            String conteudo = no.path("content").asText("");
            InfoRemoto info = new InfoRemoto();
            info.sha = no.path("sha").asText(null);
            if (!conteudo.isEmpty()) {
                info.conteudo = Base64.getDecoder().decode(conteudo.replace("\n", ""));
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static String urlConteudo() {
        return "https://api.github.com/repos/" + repo()
                + "/contents/" + dataPath() + "?ref=" + branch();
    }

    private static HttpResponse<String> enviar(String metodo, String url, String corpoJson)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "versao-java-dashboard");
        if (corpoJson != null) {
            b.header("Content-Type", "application/json")
                    .method(metodo, HttpRequest.BodyPublishers.ofString(corpoJson, StandardCharsets.UTF_8));
        } else {
            b.method(metodo, HttpRequest.BodyPublishers.noBody());
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void log(String op, String detalhe) {
        System.out.println("[GitHubSync] " + op + ": " + detalhe);
    }
}
