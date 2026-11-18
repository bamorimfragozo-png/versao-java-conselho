package br.sp.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.application.Application;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polyline;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DashboardApp extends Application {

    // --- CLASSES DTO PARA JSON (sem propriedades JavaFX) ---
    public static class DisciplinaDTO {
        public String nome;
        public Double b1;
        public Double b2;
        public Double b3;
        public Double b4;
        public Integer faltas;
    }

    public static class AlunoDTO {
        public String nome;
        public String prontuario;
        public String curso;
        public String turma;
        public Double frequencia;
        public Boolean napne;
        public String pneInfo;
        public String deliberacao;
        public List<String> acoesNapne;
        public List<DisciplinaDTO> disciplinas;
    }

    // --- MODELOS DE DADOS JAVA FX ---
    public static class Disciplina {
        private final StringProperty nome = new SimpleStringProperty();
        private final DoubleProperty b1 = new SimpleDoubleProperty();
        private final DoubleProperty b2 = new SimpleDoubleProperty();
        private final DoubleProperty b3 = new SimpleDoubleProperty();
        private final DoubleProperty b4 = new SimpleDoubleProperty();
        private final IntegerProperty faltas = new SimpleIntegerProperty();
        private final DoubleProperty media = new SimpleDoubleProperty();

        public Disciplina(String nome, Double b1, Double b2, Double b3, Double b4, int faltas) {
            this.nome.set(nome);
            this.b1.set(b1 == null ? 0 : b1);
            this.b2.set(b2 == null ? 0 : b2);
            this.b3.set(b3 == null ? 0 : b3);
            this.b4.set(b4 == null ? 0 : b4);
            this.faltas.set(faltas);
            calcularMedia();
        }

        public void calcularMedia() {
            List<Double> notas = new ArrayList<>();
            if (b1.get() > 0) notas.add(b1.get());
            if (b2.get() > 0) notas.add(b2.get());
            if (b3.get() > 0) notas.add(b3.get());
            if (b4.get() > 0) notas.add(b4.get());
            
            double mediaCalc = notas.isEmpty() ? 0 : notas.stream().mapToDouble(Double::doubleValue).sum() / notas.size();
            this.media.set(mediaCalc);
        }

        public StringProperty nomeProperty() { return nome; }
        public DoubleProperty b1Property() { return b1; }
        public DoubleProperty b2Property() { return b2; }
        public DoubleProperty b3Property() { return b3; }
        public DoubleProperty b4Property() { return b4; }
        public IntegerProperty faltasProperty() { return faltas; }
        public DoubleProperty mediaProperty() { return media; }
    }

    public static class Aluno {
        private final StringProperty nome = new SimpleStringProperty();
        private final StringProperty prontuario = new SimpleStringProperty();
        private final StringProperty curso = new SimpleStringProperty();
        private final StringProperty turma = new SimpleStringProperty();
        private final DoubleProperty frequencia = new SimpleDoubleProperty();
        private final BooleanProperty napne = new SimpleBooleanProperty();
        private final StringProperty pneInfo = new SimpleStringProperty();
        private final StringProperty deliberacao = new SimpleStringProperty();
        private final ObservableList<String> acoesNapne = FXCollections.observableArrayList();
        private final ObservableList<Disciplina> disciplinas = FXCollections.observableArrayList();

        public String getNome() { return nome.get(); }
        public void setNome(String n) { nome.set(n); }
        public String getProntuario() { return prontuario.get(); }
        public void setProntuario(String p) { prontuario.set(p); }
        public String getCurso() { return curso.get(); }
        public void setCurso(String c) { curso.set(c); }
        public String getTurma() { return turma.get(); }
        public void setTurma(String t) { turma.set(t); }
        public double getFrequencia() { return frequencia.get(); }
        public void setFrequencia(double f) { frequencia.set(f); }
        public boolean isNapne() { return napne.get(); }
        public void setNapne(boolean n) { napne.set(n); }
        public String getPneInfo() { return pneInfo.get(); }
        public void setPneInfo(String p) { pneInfo.set(p); }
        public String getDeliberacao() { return deliberacao.get(); }
        public void setDeliberacao(String d) { deliberacao.set(d); }
        public ObservableList<String> getAcoesNapne() { return acoesNapne; }
        public ObservableList<Disciplina> getDisciplinas() { return disciplinas; }
        
        @Override
        public String toString() {
            return nome.get() + " (" + prontuario.get() + ")";
        }
    }

    // --- VARIÁVEIS GLOBAIS DA UI ---
    private ObservableList<Aluno> bancoAlunos = FXCollections.observableArrayList();
    private Aluno alunoAtual;
    private File arquivoJsonAtual;
    
    private Label lblMediaGeral, lblFrequencia, lblRetencoes, lblNapne;
    private TableView<Disciplina> tableView;
    private LineChart<Number, Number> chartEvolucao;
    private CheckBox cbB1, cbB2, cbB3, cbB4;
    private TextArea txtNapne, txtDeliberacao;
    private ListView<String> listaAcoesNapne;
    private Label lblStatusArquivo;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        carregarDadosExemplo();

        primaryStage.setTitle("Conselho de Classe IFSP - Dashboard JavaFX");
        
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f4f6f9;");
        root.setPadding(new Insets(15));

        root.setTop(criarPainelSuperior());
        root.setCenter(criarPainelCentral());
        root.setBottom(criarPainelInferior());

        Scene scene = new Scene(root, 1200, 800);
        scene.setFill(Color.web("#f4f6f9"));
        
        primaryStage.setScene(scene);
        primaryStage.show();

        selecionarAluno(0);
    }

    private void carregarDadosExemplo() {
        Aluno a1 = new Aluno();
        a1.setNome("João Silva");
        a1.setProntuario("BT300123");
        a1.setCurso("Técnico em Informática");
        a1.setTurma("3º Semestre");
        a1.setFrequencia(68.0);
        a1.setNapne(true);
        a1.setPneInfo("PEI Ativo - TDAH (Tempo adicional em avaliações)");
        a1.setDeliberacao("Encaminhar para tutoria. Reunião agendada com responsáveis.");
        a1.getAcoesNapne().addAll(
            "05/03/2026: Entregue mapa mental.",
            "10/04/2026: Concedido 30min adicionais."
        );

        a1.getDisciplinas().addAll(
            new Disciplina("Algoritmos e Estrutura de Dados", 3.0, 3.5, 4.0, 3.5, 18),
            new Disciplina("Matemática Discreta", 4.0, 4.5, 5.0, 4.5, 14),
            new Disciplina("Redes de Computadores", 5.5, 5.0, 4.5, 5.0, 8),
            new Disciplina("Banco de Dados I", 3.8, 3.0, 4.2, 3.0, 10),
            new Disciplina("Programação Web I", 4.5, 5.0, 5.5, 5.0, 6),
            new Disciplina("Sistemas Operacionais", 6.0, 6.5, 7.0, 6.5, 4),
            new Disciplina("Arquitetura de Computadores", 7.0, 7.5, 8.0, 7.5, 2),
            new Disciplina("Língua Portuguesa", 8.0, 8.5, 8.0, 8.5, 2),
            new Disciplina("Inglês Técnico", 9.0, 9.0, 8.5, 9.0, 0),
            new Disciplina("Engenharia de Software", 6.5, 6.0, 7.0, 6.5, 4),
            new Disciplina("Análise de Sistemas", 7.0, 7.0, 7.5, 8.0, 2),
            new Disciplina("Ética e Cidadania", 9.5, 9.0, 9.5, 10.0, 0),
            new Disciplina("Fundamentos de Hardware", 6.0, 6.0, 6.5, 7.0, 2),
            new Disciplina("Metodologia Científica", 8.0, 8.5, 9.0, 8.5, 0),
            new Disciplina("Gestão de Projetos", 7.5, 8.0, 7.0, 7.5, 2),
            new Disciplina("Sistemas Distribuídos", null, null, 3.5, 4.0, 12),
            new Disciplina("Segurança da Informação", 6.0, 6.5, 6.0, 6.5, 4)
        );
        bancoAlunos.add(a1);
    }

    // --- CONSTRUÇÃO DA UI ---

    private VBox criarPainelSuperior() {
        VBox box = new VBox(10);
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");
        box.setPadding(new Insets(15));

        HBox topRow = new HBox(20);
        topRow.setAlignment(Pos.CENTER_LEFT);

        Label lblTitulo = new Label("Selecionar Aluno:");
        lblTitulo.setFont(Font.font("System", FontWeight.BOLD, 14));
        
        ComboBox<Aluno> cbAluno = new ComboBox<>(bancoAlunos);
        cbAluno.setOnAction(e -> selecionarAluno(cbAluno.getSelectionModel().getSelectedIndex()));

        Button btnCarregarJson = new Button("📂 Carregar JSON");
        btnCarregarJson.setStyle("-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 5;");
        btnCarregarJson.setOnAction(e -> carregarJson());

        Button btnSalvarJson = new Button("💾 Salvar JSON");
        btnSalvarJson.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 15; -fx-background-radius: 5;");
        btnSalvarJson.setOnAction(e -> salvarJson());

        lblStatusArquivo = new Label("Dados de exemplo");
        lblStatusArquivo.setStyle("-fx-text-fill: #6c757d; -fx-font-size: 11px;");

        topRow.getChildren().addAll(lblTitulo, cbAluno, btnCarregarJson, btnSalvarJson, lblStatusArquivo);

        GridPane kpiGrid = new GridPane();
        kpiGrid.setHgap(15);
        kpiGrid.setVgap(10);

        lblMediaGeral = criarKPI("MÉDIA GERAL", "0.0", "#007bff", "#e3f2fd");
        lblFrequencia = criarKPI("FREQUÊNCIA GLOBAL", "0%", "#17a2b8", "#e0f7fa");
        lblRetencoes = criarKPI("DISCIPLINAS RETIDAS (< 6.0)", "0", "#dc3545", "#f8d7da");
        lblNapne = criarKPI("ACOMPANHAMENTO NAPNE", "NÃO", "#ffc107", "#fff3cd");

        kpiGrid.add(lblMediaGeral, 0, 0);
        kpiGrid.add(lblFrequencia, 1, 0);
        kpiGrid.add(lblRetencoes, 2, 0);
        kpiGrid.add(lblNapne, 3, 0);
        GridPane.setHgrow(lblMediaGeral, Priority.ALWAYS);
        GridPane.setHgrow(lblFrequencia, Priority.ALWAYS);
        GridPane.setHgrow(lblRetencoes, Priority.ALWAYS);
        GridPane.setHgrow(lblNapne, Priority.ALWAYS);

        box.getChildren().addAll(topRow, new Separator(), kpiGrid);
        return box;
    }

    private Label criarKPI(String titulo, String valor, String corBorda, String corFundo) {
        VBox box = new VBox(5);
        box.setAlignment(Pos.CENTER);
        box.setStyle(String.format("-fx-background-color: %s; -fx-border-color: %s; -fx-border-width: 0 0 0 4; -fx-background-radius: 5; -fx-padding: 10;", corFundo, corBorda));
        
        Label lblTitulo = new Label(titulo);
        lblTitulo.setStyle("-fx-text-fill: #6c757d; -fx-font-weight: bold; -fx-font-size: 11px;");
        
        Label lblValor = new Label(valor);
        lblValor.setStyle(String.format("-fx-text-fill: %s; -fx-font-weight: bold; -fx-font-size: 24px;", corBorda));
        
        box.getChildren().addAll(lblTitulo, lblValor);
        return lblValor;
    }

    private HBox criarPainelCentral() {
        HBox mainBox = new HBox(15);
        
        VBox tableBox = new VBox(10);
        tableBox.setPrefWidth(800);
        
        tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        
        tableView.getColumns().addAll(
            criarColuna("Disciplina", "nomeProperty", 250),
            criarColuna("B1", "b1Property", 60),
            criarColuna("B2", "b2Property", 60),
            criarColuna("B3", "b3Property", 60),
            criarColuna("B4", "b4Property", 60),
            criarColuna("Média", "mediaProperty", 70),
            criarColuna("Faltas", "faltasProperty", 60),
            criarColunaSparkline("Evolução")
        );

        Label lblTableTitle = new Label("Desempenho por Disciplina");
        lblTableTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        lblTableTitle.setStyle("-fx-text-fill: #495057;");
        
        tableBox.getChildren().addAll(lblTableTitle, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        VBox chartBox = new VBox(15);
        chartBox.setPrefWidth(350);
        chartBox.setStyle("-fx-background-color: white; -fx-background-radius: 10; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");
        chartBox.setPadding(new Insets(15));

        Label lblChartTitle = new Label("Evolução da Média Geral");
        lblChartTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        lblChartTitle.setStyle("-fx-text-fill: #495057;");

        HBox checkPanel = new HBox(15);
        checkPanel.setAlignment(Pos.CENTER);
        checkPanel.setStyle("-fx-background-color: #f8f9fa; -fx-background-radius: 5; -fx-padding: 10; -fx-border-color: #dee2e6; -fx-border-radius: 5;");
        
        cbB1 = new CheckBox("1º Bim"); cbB1.setSelected(true);
        cbB2 = new CheckBox("2º Bim"); cbB2.setSelected(true);
        cbB3 = new CheckBox("3º Bim"); cbB3.setSelected(true);
        cbB4 = new CheckBox("4º Bim"); cbB4.setSelected(true);

        Runnable atualizar = this::atualizarDashboard;
        cbB1.selectedProperty().addListener((obs, old, val) -> atualizar.run());
        cbB2.selectedProperty().addListener((obs, old, val) -> atualizar.run());
        cbB3.selectedProperty().addListener((obs, old, val) -> atualizar.run());
        cbB4.selectedProperty().addListener((obs, old, val) -> atualizar.run());

        checkPanel.getChildren().addAll(cbB1, cbB2, cbB3, cbB4);

        NumberAxis xAxis = new NumberAxis(1, 4, 1);
        xAxis.setLabel("Bimestre");
        NumberAxis yAxis = new NumberAxis(0, 10, 2);
        yAxis.setLabel("Média");
        
        chartEvolucao = new LineChart<>(xAxis, yAxis);
        chartEvolucao.setTitle("");
        chartEvolucao.setLegendVisible(false);
        chartEvolucao.setCreateSymbols(true);
        chartEvolucao.setStyle("-fx-font-size: 12px;");

        chartBox.getChildren().addAll(lblChartTitle, checkPanel, chartEvolucao);
        VBox.setVgrow(chartEvolucao, Priority.ALWAYS);

        mainBox.getChildren().addAll(tableBox, chartBox);
        HBox.setHgrow(tableBox, Priority.ALWAYS);
        
        return mainBox;
    }

    private VBox criarPainelInferior() {
        VBox box = new VBox(10);
        box.setStyle("-fx-background-color: #fff8e1; -fx-background-radius: 10; -fx-border-color: #ffa000; -fx-border-width: 0 0 0 4; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.05), 10, 0, 0, 2);");
        box.setPadding(new Insets(15));

        Label lblTitle = new Label("Parecer do NAPNE & Observações Pedagógicas");
        lblTitle.setFont(Font.font("System", FontWeight.BOLD, 14));

        VBox napneBox = new VBox(10);
        Label lblNapneTitle = new Label("Anotações / Adaptações Curriculares (NAPNE):");
        lblNapneTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #6c757d;");
        
        txtNapne = new TextArea();
        txtNapne.setPromptText("Nenhum registro encontrado.");
        txtNapne.setPrefRowCount(3);

        HBox acaoBox = new HBox(10);
        TextField inputNovaAcao = new TextField();
        inputNovaAcao.setPromptText("Adicionar nova ação pedagógica/NAPNE...");
        HBox.setHgrow(inputNovaAcao, Priority.ALWAYS);
        
        Button btnSalvarAcao = new Button("Salvar Ação");
        btnSalvarAcao.setStyle("-fx-background-color: #ffc107; -fx-font-weight: bold;");
        btnSalvarAcao.setOnAction(e -> {
            String acao = inputNovaAcao.getText().trim();
            if (!acao.isEmpty()) {
                String dataAtual = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
                alunoAtual.getAcoesNapne().add(dataAtual + ": " + acao);
                listaAcoesNapne.setItems(alunoAtual.getAcoesNapne());
                inputNovaAcao.clear();
            }
        });
        acaoBox.getChildren().addAll(inputNovaAcao, btnSalvarAcao);

        Label lblHistorico = new Label("Histórico de Ações Registradas:");
        lblHistorico.setStyle("-fx-font-weight: bold; -fx-text-fill: #6c757d;");
        
        listaAcoesNapne = new ListView<>();
        listaAcoesNapne.setPrefHeight(100);
        listaAcoesNapne.setStyle("-fx-background-color: white; -fx-border-color: #dee2e6;");

        napneBox.getChildren().addAll(lblNapneTitle, txtNapne, acaoBox, lblHistorico, listaAcoesNapne);

        VBox deliberacaoBox = new VBox(10);
        Label lblDeliberacaoTitle = new Label("Encaminhamento / Deliberação do Conselho de Classe:");
        lblDeliberacaoTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #6c757d;");
        
        txtDeliberacao = new TextArea();
        txtDeliberacao.setPromptText("Digite as deliberações do conselho...");
        txtDeliberacao.setPrefRowCount(4);

        Button btnSalvarDeliberacao = new Button("Salvar Deliberação");
        btnSalvarDeliberacao.setStyle("-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-weight: bold;");
        btnSalvarDeliberacao.setOnAction(e -> {
            alunoAtual.setDeliberacao(txtDeliberacao.getText());
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Deliberação salva com sucesso!");
            alert.showAndWait();
        });

        deliberacaoBox.getChildren().addAll(lblDeliberacaoTitle, txtDeliberacao, btnSalvarDeliberacao);

        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(10);
        grid.add(napneBox, 0, 0);
        grid.add(deliberacaoBox, 1, 0);
        GridPane.setHgrow(napneBox, Priority.ALWAYS);
        GridPane.setHgrow(deliberacaoBox, Priority.ALWAYS);

        box.getChildren().addAll(lblTitle, grid);
        return box;
    }

    // --- LÓGICA DE NEGÓCIO E ATUALIZAÇÃO ---

    private void selecionarAluno(int index) {
        if (index < 0 || index >= bancoAlunos.size()) return;
        alunoAtual = bancoAlunos.get(index);
        
        txtNapne.setText(alunoAtual.getPneInfo());
        txtDeliberacao.setText(alunoAtual.getDeliberacao());
        listaAcoesNapne.setItems(alunoAtual.getAcoesNapne());
        
        atualizarDashboard();
    }

    private void atualizarDashboard() {
        if (alunoAtual == null) return;

        lblFrequencia.setText(String.format("%.1f%%", alunoAtual.getFrequencia()));
        lblFrequencia.setStyle(alunoAtual.getFrequencia() < 75 ? 
            "-fx-text-fill: #dc3545; -fx-font-weight: bold; -fx-font-size: 24px;" : 
            "-fx-text-fill: #17a2b8; -fx-font-weight: bold; -fx-font-size: 24px;");
            
        lblNapne.setText(alunoAtual.isNapne() ? "SIM" : "NÃO");

        List<Disciplina> discs = alunoAtual.getDisciplinas();

        double somaMedias = 0;
        int countMedias = 0;
        int retencoes = 0;

        for (Disciplina d : discs) {
            List<Double> notas = new ArrayList<>();
            if (cbB1.isSelected() && d.b1.get() > 0) notas.add(d.b1.get());
            if (cbB2.isSelected() && d.b2.get() > 0) notas.add(d.b2.get());
            if (cbB3.isSelected() && d.b3.get() > 0) notas.add(d.b3.get());
            if (cbB4.isSelected() && d.b4.get() > 0) notas.add(d.b4.get());

            double media = notas.isEmpty() ? 0 : notas.stream().mapToDouble(Double::doubleValue).sum() / notas.size();
            d.media.set(media);

            if (media > 0) {
                somaMedias += media;
                countMedias++;
                if (media < 6.0) retencoes++;
            }
        }

        double mediaGeral = countMedias > 0 ? somaMedias / countMedias : 0;
        lblMediaGeral.setText(String.format("%.1f", mediaGeral));
        lblMediaGeral.setStyle(String.format("-fx-text-fill: %s; -fx-font-weight: bold; -fx-font-size: 24px;", 
            mediaGeral < 4 ? "#dc3545" : (mediaGeral < 6 ? "#fd7e14" : "#198754")));
            
        lblRetencoes.setText(String.valueOf(retencoes));

        ObservableList<Disciplina> sortedList = FXCollections.observableArrayList(discs);
        sortedList.sort((d1, d2) -> Double.compare(d1.media.get(), d2.media.get()));
        tableView.setItems(sortedList);

        atualizarGrafico();
    }

    private void atualizarGrafico() {
        chartEvolucao.getData().clear();
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        
        double[] mediasBimestrais = new double[4];
        int[] counts = new int[4];

        for (Disciplina d : alunoAtual.getDisciplinas()) {
            if (cbB1.isSelected() && d.b1.get() > 0) { mediasBimestrais[0] += d.b1.get(); counts[0]++; }
            if (cbB2.isSelected() && d.b2.get() > 0) { mediasBimestrais[1] += d.b2.get(); counts[1]++; }
            if (cbB3.isSelected() && d.b3.get() > 0) { mediasBimestrais[2] += d.b3.get(); counts[2]++; }
            if (cbB4.isSelected() && d.b4.get() > 0) { mediasBimestrais[3] += d.b4.get(); counts[3]++; }
        }

        for (int i = 0; i < 4; i++) {
            if ((i == 0 && cbB1.isSelected()) || (i == 1 && cbB2.isSelected()) || 
                (i == 2 && cbB3.isSelected()) || (i == 3 && cbB4.isSelected())) {
                
                double media = counts[i] > 0 ? mediasBimestrais[i] / counts[i] : 0;
                XYChart.Data<Number, Number> data = new XYChart.Data<>(i + 1, media);
                
                String cor = media < 4 ? "#dc3545" : (media < 6 ? "#fd7e14" : "#198754");
                data.nodeProperty().addListener((obs, old, n) -> {
                    if (n != null) n.setStyle("-fx-background-color: " + cor + ", white; -fx-background-insets: 0, 2; -fx-background-radius: 5px; -fx-padding: 5px;");
                });
                
                series.getData().add(data);
            }
        }

        chartEvolucao.getData().add(series);
        series.getNode().setStyle("-fx-stroke: #fd7e14; -fx-stroke-width: 3px;");
    }

    // --- UTILITÁRIOS DE UI ---

    private <T> TableColumn<Disciplina, T> criarColuna(String titulo, String property, double width) {
        TableColumn<Disciplina, T> col = new TableColumn<>(titulo);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        col.setStyle("-fx-alignment: CENTER;");
        
        if (property.contains("b") || property.contains("media")) {
            col.setCellFactory(column -> new TableCell<Disciplina, T>() {
                @Override
                protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                    } else {
                        double val = ((Number) item).doubleValue();
                        setText(val == 0 ? "-" : String.format("%.1f", val));
                        if (val < 4.0) setStyle("-fx-text-fill: #dc3545; -fx-font-weight: bold; -fx-alignment: center;");
                        else if (val < 6.0) setStyle("-fx-text-fill: #fd7e14; -fx-font-weight: bold; -fx-alignment: center;");
                        else setStyle("-fx-text-fill: #198754; -fx-font-weight: bold; -fx-alignment: center;");
                    }
                }
            });
        }
        return col;
    }

    private TableColumn<Disciplina, Void> criarColunaSparkline(String titulo) {
        TableColumn<Disciplina, Void> col = new TableColumn<>(titulo);
        col.setPrefWidth(120);
        col.setCellFactory(column -> new TableCell<Disciplina, Void>() {
            private final Polyline polyline = new Polyline();
            
            {
                polyline.setStrokeWidth(2);
                polyline.setFill(null);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }

                Disciplina d = getTableRow().getItem();
                double[] vals = {d.b1.get(), d.b2.get(), d.b3.get(), d.b4.get()};
                
                double min = 0, max = 10;
                double stepX = 100.0 / 3;
                
                StringBuilder points = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    double x = i * stepX;
                    double y = 40 - ((vals[i] - min) / (max - min) * 40);
                    points.append(x).append(",").append(y).append(" ");
                }
                
                polyline.getPoints().clear();
                String[] p = points.toString().trim().split(" ");
                for (String pt : p) {
                    String[] coords = pt.split(",");
                    polyline.getPoints().addAll(Double.parseDouble(coords[0]), Double.parseDouble(coords[1]));
                }

                double media = d.media.get();
                if (media < 4.0) polyline.setStroke(Color.web("#dc3545"));
                else if (media < 6.0) polyline.setStroke(Color.web("#fd7e14"));
                else polyline.setStroke(Color.web("#198754"));

                setGraphic(polyline);
                setAlignment(Pos.CENTER);
            }
        });
        return col;
    }

    // --- CARREGAMENTO E SALVAMENTO DE JSON ---

    private Aluno converterDTOparaAluno(AlunoDTO dto) {
        Aluno aluno = new Aluno();
        aluno.setNome(dto.nome);
        aluno.setProntuario(dto.prontuario);
        aluno.setCurso(dto.curso);
        aluno.setTurma(dto.turma);
        aluno.setFrequencia(dto.frequencia != null ? dto.frequencia : 0);
        aluno.setNapne(dto.napne != null ? dto.napne : false);
        aluno.setPneInfo(dto.pneInfo != null ? dto.pneInfo : "");
        aluno.setDeliberacao(dto.deliberacao != null ? dto.deliberacao : "");
        
        if (dto.acoesNapne != null) {
            aluno.getAcoesNapne().addAll(dto.acoesNapne);
        }
        
        if (dto.disciplinas != null) {
            for (DisciplinaDTO discDTO : dto.disciplinas) {
                Disciplina disc = new Disciplina(
                    discDTO.nome,
                    discDTO.b1,
                    discDTO.b2,
                    discDTO.b3,
                    discDTO.b4,
                    discDTO.faltas != null ? discDTO.faltas : 0
                );
                aluno.getDisciplinas().add(disc);
            }
        }
        
        return aluno;
    }

    private AlunoDTO converterAlunoparaDTO(Aluno aluno) {
        AlunoDTO dto = new AlunoDTO();
        dto.nome = aluno.getNome();
        dto.prontuario = aluno.getProntuario();
        dto.curso = aluno.getCurso();
        dto.turma = aluno.getTurma();
        dto.frequencia = aluno.getFrequencia();
        dto.napne = aluno.isNapne();
        dto.pneInfo = aluno.getPneInfo();
        dto.deliberacao = aluno.getDeliberacao();
        dto.acoesNapne = new ArrayList<>(aluno.getAcoesNapne());
        
        dto.disciplinas = new ArrayList<>();
        for (Disciplina disc : aluno.getDisciplinas()) {
            DisciplinaDTO discDTO = new DisciplinaDTO();
            discDTO.nome = disc.nomeProperty().get();
            discDTO.b1 = disc.b1Property().get() > 0 ? disc.b1Property().get() : null;
            discDTO.b2 = disc.b2Property().get() > 0 ? disc.b2Property().get() : null;
            discDTO.b3 = disc.b3Property().get() > 0 ? disc.b3Property().get() : null;
            discDTO.b4 = disc.b4Property().get() > 0 ? disc.b4Property().get() : null;
            discDTO.faltas = disc.faltasProperty().get();
            dto.disciplinas.add(discDTO);
        }
        
        return dto;
    }

    private void carregarJson() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Carregar Arquivo JSON");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Arquivos JSON", "*.json")
        );
        
        File arquivo = fileChooser.showOpenDialog(null);
        if (arquivo != null) {
            try (FileReader reader = new FileReader(arquivo)) {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<AlunoDTO>>(){}.getType();
                List<AlunoDTO> alunosDTO = gson.fromJson(reader, listType);
                
                bancoAlunos.clear();
                for (AlunoDTO dto : alunosDTO) {
                    bancoAlunos.add(converterDTOparaAluno(dto));
                }
                
                arquivoJsonAtual = arquivo;
                lblStatusArquivo.setText("Carregado: " + arquivo.getName() + " (" + alunosDTO.size() + " alunos)");
                
                selecionarAluno(0);
                
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Arquivo carregado com sucesso!");
                alert.showAndWait();
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Erro ao carregar arquivo: " + e.getMessage());
                alert.showAndWait();
            }
        }
    }

    private void salvarJson() {
        if (arquivoJsonAtual == null) {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Salvar Arquivo JSON");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Arquivos JSON", "*.json")
            );
            arquivoJsonAtual = fileChooser.showSaveDialog(null);
        }
        
        if (arquivoJsonAtual != null) {
            try (FileWriter writer = new FileWriter(arquivoJsonAtual)) {
                List<AlunoDTO> alunosDTO = new ArrayList<>();
                for (Aluno aluno : bancoAlunos) {
                    alunosDTO.add(converterAlunoparaDTO(aluno));
                }
                
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                gson.toJson(alunosDTO, writer);
                lblStatusArquivo.setText("Salvo: " + arquivoJsonAtual.getName());
                
                Alert alert = new Alert(Alert.AlertType.INFORMATION, "Arquivo salvo com sucesso!");
                alert.showAndWait();
            } catch (Exception e) {
                Alert alert = new Alert(Alert.AlertType.ERROR, "Erro ao salvar arquivo: " + e.getMessage());
                alert.showAndWait();
            }
        }
    }
}