package com.todoapp.shared;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.stage.Stage;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.beans.binding.Bindings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
public class MainBorderPane extends Application {
    private static MainBorderPane instance;
    public MainBorderPane() { instance = this; }
    public static MainBorderPane getInstance() { return instance; }
    private TaskDataManager dataManager; // 声明数据管理器
    private boolean isDataDirty = false; // 标记数据是否修改
    private ScheduledExecutorService autoSaveService; // 添加自动保存服务
    private boolean syncEnabled = true; // 默认开启云同步
    private ToggleButton syncToggleBtn;  // 云同步开关按钮
    // 任务按完成状态分开存储（全局任务）
    ObservableList<Task> uncompletedTasks = FXCollections.observableArrayList();
    ObservableList<Task> completedTasks = FXCollections.observableArrayList();
    ObservableList<TaskList> customLists = FXCollections.observableArrayList();
    ObservableList<Task> deletedTasks = FXCollections.observableArrayList();
    private VBox leftMenu;
    private ScrollPane contentArea;
    private Label currentViewLabel;
    private String currentView = "今天";
    private VBox taskContainer;
    private TextField taskInput;
    Label statusLabel = new Label();
    private TaskNetworkService networkService;
    private String userId;
    private final StringProperty syncStatus = new SimpleStringProperty("未同步");
    private final BooleanProperty isOnline = new SimpleBooleanProperty(false);
    // 增量同步变更集
    private final ArrayList<Task> changedTasks = new ArrayList<>();
    private final ArrayList<TaskList> changedLists = new ArrayList<>();

    @Override
    public void start(Stage primaryStage) {
        // 启动时弹窗输入用户名
        userId = askUserId();
        if (userId == null || userId.trim().isEmpty()) {
            Platform.exit();
            return;
        }
        // 初始化数据管理器和网络服务
        dataManager = new TaskDataManager(this, userId);
        networkService = new TaskNetworkService("http://localhost:8080");
        networkService.setUserId(userId);
        networkService.setCallbacks(this::handleNetworkData, this::handleNetworkError);
        BorderPane mainStage = new BorderPane();
        mainStage.setStyle("-fx-background-color: #f5f5f5;");
        mainStage.setTop(createTopToolBar());
        Scene scene = new Scene(mainStage, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("无法加载 CSS 文件: " + e.getMessage());
        }
        leftMenu = createLeftNavigation();
        mainStage.setLeft(leftMenu);
        contentArea = createContentArea();
        mainStage.setCenter(contentArea);
        primaryStage.setTitle("任务管理");
        primaryStage.setScene(scene);
        primaryStage.show();
        Platform.runLater(() -> {
            networkService.fetchTasks().thenAccept(remoteData -> {
                Platform.runLater(() -> {
                    dataManager.loadData();
                    // 在数据加载完成后，检查并创建默认清单
                    initializeDefaultLists();
                    // 启动自动保存服务
                    setupAutoSave();
                    // 刷新界面
                    refreshCurrentView();
                    refreshCustomListsUI();
                });
            });
        });
        // 添加窗口关闭事件处理
        primaryStage.setOnCloseRequest(event -> {
            saveAndShutdown();
        });
        primaryStage.show();
        // 添加定期同步
        setupPeriodicSync();
        // 添加同步状态显示
        Label syncStatusLabel = new Label();
        syncStatusLabel.textProperty().bind(syncStatus);
        syncStatusLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 12px;");
        // 添加在线状态指示器
        Circle onlineIndicator = new Circle(5);
        onlineIndicator.styleProperty().bind(Bindings.createStringBinding(() ->
            isOnline.get() ? "-fx-fill: #4CAF50;" : "-fx-fill: #F44336;",
            isOnline
        ));
        HBox statusBar = new HBox(10, onlineIndicator, syncStatusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5));
        mainStage.setBottom(statusBar);
        // 启动定时拉取并合并服务器数据
        setupPeriodicPullAndMerge();
    }
    //错误弹窗
    void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
    private ToolBar createTopToolBar() {
        ToolBar toolBar = new ToolBar();
        toolBar.setPadding(new Insets(10));
        // 初始化并设置状态标签
        statusLabel = new Label();
        updateStatusLabel(); // 初始化状态显示
        // 云同步开关按钮
        syncToggleBtn = new ToggleButton("云同步：开");
        syncToggleBtn.setSelected(true);
        syncToggleBtn.setStyle("-fx-padding: 0 8 0 8; -fx-background-radius: 4; -fx-border-radius: 4;");
        syncToggleBtn.setOnAction(e -> {
            syncEnabled = syncToggleBtn.isSelected();
            syncToggleBtn.setText(syncEnabled ? "云同步：开" : "云同步：关");
            if (syncEnabled) {
                setupPeriodicSync();
                syncToServer();
                syncStatus.set("云同步已开启");
            } else {
                syncStatus.set("本地模式（不同步）");
            }
        });
        toolBar.getItems().addAll(statusLabel, new Separator(), syncToggleBtn);
        return toolBar;
    }
    void markDataAsDirty() {
        System.out.println("数据已标记为修改");
        isDataDirty = true;
        updateStatusLabel();
        // 确保数据修改后立即触发自动保存
        if (autoSaveService == null || autoSaveService.isShutdown()) {
            setupAutoSave();
        }
    }
    private void setupAutoSave() {
        System.out.println("设置自动保存服务");
        if (autoSaveService != null && !autoSaveService.isShutdown()) {
            return; // 如果服务已经在运行，则不需要重新创建
        }
        autoSaveService = Executors.newSingleThreadScheduledExecutor();
        autoSaveService.scheduleAtFixedRate(
                () -> {
                    Platform.runLater(() -> {
                        if (isDataDirty) {
                            System.out.println("执行自动保存...");
                            dataManager.saveData(true); // 使用静默保存
                            isDataDirty = false;
                            updateStatusLabel();
                            System.out.println("自动保存完成");
                            if (syncEnabled) {
                                syncToServer();
                            }
                        }
                    });
                },
                2, // 首次保存延迟2s
                2, // 之后每2s保存一次
                TimeUnit.SECONDS
        );
        System.out.println("自动保存服务已启动");
    }
    private String askUserId() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("登录");
        dialog.setHeaderText("请输入你的通道选择");
        dialog.setContentText("通道：");
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }
    private void setupPeriodicPullAndMerge() {
        ScheduledExecutorService pullService = Executors.newSingleThreadScheduledExecutor();
        pullService.scheduleAtFixedRate(() -> {
            if (syncEnabled) {
                networkService.fetchTasks().thenAccept(remoteData -> {
                    dataManager.mergeAndSave(remoteData);
                    Platform.runLater(this::refreshCurrentView);
                });
            }
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
    }
    private void updateStatusLabel() {
        if (statusLabel != null) {
            Platform.runLater(() -> {
                statusLabel.setText("数据状态: " + (isDataDirty ? "已修改" : "未修改"));
                System.out.println("状态标签已更新: " + (isDataDirty ? "已修改" : "未修改"));
            });
        }
    }
    // 添加新方法：初始化默认清单（只在没有时创建一次）
    private void initializeDefaultLists() {
        // 检查是否已存在"加油哦"清单，如果不存在则创建
        if (!TaskList.isNameExists(customLists, "加油哦")) {
            System.out.println("创建默认清单：加油哦");
            TaskList defaultList = new TaskList("加油哦");
            customLists.add(defaultList);
            // 刷新界面显示
            refreshCustomListsUI();
            markDataAsDirty();
        }
    }
    // 保存数据并关闭自动保存服务
    private void saveAndShutdown() {//修改
        try {
            // 如果数据有修改，执行最后一次保存
            dataManager.saveData();
            isDataDirty = false;
            // 关闭自动保存服务
            if (autoSaveService != null && !autoSaveService.isShutdown()) {
                autoSaveService.shutdown();
                // 等待任务终止（最多等待1秒）
                if (!autoSaveService.awaitTermination(1, TimeUnit.SECONDS)) {
                    autoSaveService.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            // 恢复中断状态
            Thread.currentThread().interrupt();
        }
    }
    // 应用关闭时调用
    @Override
    public void stop() {
        saveAndShutdown();
        shutdown();
        System.out.println("应用已关闭，自动保存服务已停止");
    }
    private VBox createLeftNavigation() {
        VBox leftNav = new VBox(5);
        leftNav.setPadding(new Insets(15));
        leftNav.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-width: 0 1 0 0;");
        leftNav.setPrefWidth(250);
        // 标题
        Label titleLabel = new Label("📝 任务管理");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 18));
        titleLabel.setPadding(new Insets(0, 0, 15, 0));
        // 主要导航按钮（直接放在外面）
        Button inboxBtn = createDefaultNavButton("📥 收集箱", "收集箱");
        Button todayBtn = createDefaultNavButton("📅 今天", "今天");
        Button completedBtn = createDefaultNavButton("✅ 已完成", "已完成");
        // 设置今天按钮为默认选中状态
        todayBtn.setStyle("-fx-background-color: #e3f2fd; -fx-font-size: 14px; -fx-padding: 8px 15px;");
        // 分隔线
        Separator separator = new Separator();
        separator.setPadding(new Insets(10, 0, 10, 0));
        // 自定义清单容器
        VBox customListsContainer = new VBox(3);
        // 创建自定义箭头
        Polygon arrow = new Polygon(
                0.0, 0.0,     // 左上点
                5.0, 6.0,     // 中间顶点
                10.0, 0.0,    // 右上点
                8.0, 0.0,     // 右上内部点
                5.0, 4.0,     // 中间内部顶点
                2.0, 0.0      // 左上内部点
        );
        arrow.setFill(Color.TRANSPARENT);
        arrow.setStroke(Color.GRAY);
        arrow.setStrokeWidth(1);
        // 创建TitledPane作为自定义清单的容器
        TitledPane customListsPane = new TitledPane();
        customListsPane.setContent(customListsContainer);
        customListsPane.setExpanded(true);
        // 创建清单标题栏
        Button addListBtn = new Button("+");
        addListBtn.setStyle("-fx-font-size: 22px; -fx-background-color: transparent;");
        addListBtn.setOnAction(e -> showNewListDialog());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label titleText = new Label("自定义清单");
        titleText.setStyle("-fx-font-size: 14px;");
        HBox titleBox = new HBox(5);
        titleBox.getChildren().addAll(titleText, spacer, addListBtn);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        customListsPane.setGraphic(titleBox);
        // 将所有组件添加到左侧导航栏
        leftNav.getChildren().addAll(
                titleLabel,
                inboxBtn,
                todayBtn,
                completedBtn,
                separator,
                customListsPane
        );
        return leftNav;
    }
    // 刷新自定义清单UI时，为每个清单按钮添加删除功能
    void refreshCustomListsUI() {
        // 获取自定义清单容器
        TitledPane customListsPane = (TitledPane) leftMenu.getChildren().get(5);
        VBox customListsContainer = (VBox) customListsPane.getContent();
        customListsContainer.getChildren().clear();
        // 添加所有自定义清单
        for (TaskList list : customLists) {
            Button listBtn = createCustomNavButton("\u2630 " + list.getName(), list);
            customListsContainer.getChildren().add(listBtn);
        }
        // 确保清单区域可见
        customListsPane.setExpanded(true);
    }
    private Button createDefaultNavButton(String text, String view) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
        button.setOnMouseEntered(e ->
                button.setStyle("-fx-background-color: #f0f0f0; -fx-text-fill: #212121; -fx-font-size: 14px;"));
        button.setOnMouseExited(e -> {
            if (!view.equals(currentView)) {
                button.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
            }
        });
        button.setOnAction(e -> {
            currentView = view;
            refreshCurrentView();
            updateNavButtonStyles(button);
        });
        return button;
    }
    private Button createCustomNavButton(String text, TaskList list) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
        button.setUserData(list);
        button.setOnMouseEntered(e ->
                button.setStyle("-fx-background-color: #f0f0f0; -fx-text-fill: #212121; -fx-font-size: 14px;"));
        button.setOnMouseExited(e -> {
            if (!list.getName().equals(currentView)) {
                button.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
            }
        });
        button.setOnAction(e -> {
            currentView = list.getName();
            refreshCurrentView();
            updateNavButtonStyles(button);
        });
        button.setOnContextMenuRequested(e -> {
            ContextMenu contextMenu = new ContextMenu();
            MenuItem deleteItem = new MenuItem("删除清单");
            deleteItem.setOnAction(event -> {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("删除清单");
                alert.setHeaderText("确定要删除清单 \"" + list.getName() + "\" 吗？");
                alert.setContentText("此操作将删除该清单及其所有任务。");

                Optional<ButtonType> result = alert.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    customLists.remove(list);
                    refreshCustomListsUI();
                    if (currentView.equals(list.getName())) {
                        currentView = "今天";
                        refreshCurrentView();
                    }
                    markDataAsDirty();
                }
            });
            contextMenu.getItems().add(deleteItem);
            contextMenu.show(button, e.getScreenX(), e.getScreenY());
        });
        return button;
    }
    private void updateNavButtonStyles(Button selectedButton) {
        // 更新默认导航按钮的样式
        for (Node node : leftMenu.getChildren()) {
            if (node instanceof Button) {
                Button btn = (Button) node;
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
            }
        }
        // 更新自定义清单按钮的样式
        TitledPane customListsPane = (TitledPane) leftMenu.getChildren().get(5);
        VBox customListsContainer = (VBox) customListsPane.getContent();
        for (Node node : customListsContainer.getChildren()) {
            if (node instanceof Button) {
                Button btn = (Button) node;
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #212121; -fx-font-size: 14px;");
            }
        }
        // 设置选中按钮的样式
        selectedButton.setStyle("-fx-background-color: #e3f2fd; -fx-text-fill: #212121; -fx-font-size: 14px;");
    }
    private ScrollPane createContentArea() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #ffffff;");
        VBox content = new VBox(20);
        content.setPadding(new Insets(30));
        content.setStyle("-fx-background-color: #ffffff;");
        // 当前视图标题
        currentViewLabel = new Label("今天");
        currentViewLabel.setFont(Font.font("System", FontWeight.BOLD, 24));
        currentViewLabel.setPadding(new Insets(0, 0, 20, 0));
        // 任务输入框（收集箱任务）
        taskInput = new TextField();
        taskInput.setPromptText("添加任务，回车即可创建！");
        taskInput.setStyle("-fx-font-size: 16px; -fx-padding: 12px; -fx-border-color: #ddd; " +
                "-fx-border-radius: 5px; -fx-background-radius: 5px;");
        taskInput.setPrefHeight(45);
        setupTaskInput();
        taskInput.setOnAction(e -> {
            String taskName = taskInput.getText().trim();
            if (!taskName.isEmpty()) {
                addTaskToCurrentView(taskName);
            }
        });
        // 任务容器
        taskContainer = new VBox(5);
        taskContainer.setPadding(new Insets(0, 10, 0, 10));
        content.getChildren().addAll(currentViewLabel, taskInput, taskContainer);
        scrollPane.setContent(content);
        return scrollPane;
    }
    void refreshCurrentView() {
        taskContainer.getChildren().clear();
        taskContainer.setSpacing(15);
        ObservableList<Task> uncompletedTasksList = FXCollections.observableArrayList();
        ObservableList<Task> completedTasksList = FXCollections.observableArrayList();
        switch (currentView) {
            case "今天":
            case "收集箱":
                uncompletedTasksList.addAll(uncompletedTasks.filtered(t -> !t.isCompleted()));
                completedTasksList.addAll(uncompletedTasks.filtered(Task::isCompleted));
                completedTasksList.addAll(completedTasks.filtered(Task::isCompleted));
                break;
            case "已完成":
                completedTasksList.addAll(uncompletedTasks.filtered(Task::isCompleted));
                completedTasksList.addAll(completedTasks.filtered(Task::isCompleted));
                break;
            default:
                TaskList customList = customLists.stream()
                        .filter(list -> list.getName().equals(currentView))
                        .findFirst().orElse(null);
                if (customList != null) {
                    uncompletedTasksList.addAll(customList.getTasks().filtered(task -> !task.isCompleted()));
                    completedTasksList.addAll(customList.getTasks().filtered(Task::isCompleted));
                }
                break;
        }
        TitledPane uncompletedPane = createTaskSection("未完成任务", uncompletedTasksList, false);
        uncompletedPane.setExpanded(true);
        taskContainer.getChildren().add(uncompletedPane);
        TitledPane completedPane = createTaskSection("已完成任务", completedTasksList, true);
        completedPane.setExpanded(true);
        taskContainer.getChildren().add(completedPane);
        currentViewLabel.setText(currentView);
    }
    private TitledPane createTaskSection(String title, ObservableList<Task> tasks, boolean isCompleted) {
        VBox content = new VBox(8);  // 增加任务项之间的间距
        content.setPadding(new Insets(10, 15, 10, 15));  // 增加内边距
        content.setStyle("-fx-background-color: transparent;");
        // 添加任务项
        tasks.forEach(task -> content.getChildren().add(createTaskItem(task)));
        // 如果没有任务，添加提示文本
        if (tasks.isEmpty()) {
            Label emptyLabel = new Label(isCompleted ? "没有已完成的任务" : "没有待办任务");
            emptyLabel.setStyle("-fx-text-fill: #999999; -fx-font-size: 14px;");
            emptyLabel.setPadding(new Insets(10, 0, 10, 0));
            content.getChildren().add(emptyLabel);
        }
        // 设置标题样式
        Label titleLabel = new Label(title + " (" + tasks.size() + ")");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        if (isCompleted) {
            titleLabel.setStyle(titleLabel.getStyle() + "; -fx-text-fill: #666666;");
        }
        // 创建TitledPane，不设置文本（因为我们使用Label作为graphic）
        TitledPane titledPane = new TitledPane();
        titledPane.setGraphic(titleLabel);
        titledPane.setContent(content);
        titledPane.getStyleClass().add("task-section");
        // 如果是已完成任务，添加completed样式类
        if (isCompleted) {
            titledPane.getStyleClass().add("completed");
            // titleLabel.setStyle(titleLabel.getStyle() + "; -fx-text-fill: #666666;");
        }
        // 添加展开/折叠动画效果
        titledPane.expandedProperty().addListener((obs, oldVal, newVal) -> {
            content.setVisible(newVal);
            content.setManaged(newVal);
        });
        return titledPane;
    }

    private HBox createTaskItem(Task task) {
        HBox container = new HBox(15);
        container.setAlignment(Pos.CENTER_LEFT);
        container.setPadding(new Insets(10, 15, 10, 15));
        container.setStyle("-fx-background-color: transparent;");
        CheckBox checkBox = new CheckBox();
        checkBox.setStyle("-fx-font-size: 14px;");
        Label taskLabel = new Label();
        taskLabel.setStyle("-fx-font-size: 14px;");
        // 创建右侧容器并设置任务标签自动扩展
        HBox rightContainer = new HBox(5);
        rightContainer.getChildren().add(taskLabel);
        rightContainer.setHgrow(taskLabel, Priority.ALWAYS);
        // 创建删除按钮（统一文本为图标）
        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: black; -fx-font-size: 14px; -fx-padding: 0 5px;");
        deleteBtn.setOnAction(e -> {
            deleteTask(task);
            container.getChildren().remove(deleteBtn);
        });
        // 使用事件过滤器确保右键被捕获
        container.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                event.consume(); // 阻止事件传播
                // 确保删除按钮只添加一次
                if (!container.getChildren().contains(deleteBtn)) {
                    // 移除其他任务项的删除按钮
                    taskContainer.getChildren().forEach(node -> {
                        if (node instanceof HBox) {
                            HBox hbox = (HBox) node;
                            hbox.getChildren().removeIf(child ->
                                    child instanceof Button && ((Button) child).getText().equals("删除")
                            );
                        }
                    });
                    // 使用Region作为弹性间隔，将删除按钮推到最右侧
                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);
                    // 添加删除按钮到HBox尾部
                    container.getChildren().addAll(spacer,deleteBtn);
                    // 鼠标离开时隐藏按钮
                    container.setOnMouseExited(e -> {
                        container.getChildren().remove(deleteBtn);
                    });
                }
            }
        });
        // 添加组件到HBox（确保任务标签占据空间）
        container.getChildren().addAll(checkBox, rightContainer);
        // 鼠标悬停效果
        container.setOnMouseEntered(e ->
                container.setStyle("-fx-background-color: #f8f8f8; -fx-background-radius: 5px;"));
        container.setOnMouseExited(e ->
                container.setStyle("-fx-background-color: transparent;"));
        // 绑定数据
        taskLabel.textProperty().bind(task.nameProperty());
        checkBox.selectedProperty().bindBidirectional(task.completedProperty());
        // 初始样式设置
        if (task.isCompleted()) {
            taskLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #888; -fx-strikethrough: true;");
        } else {
            taskLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #333;");
        }
        return container;
    }
    private void deleteTask(Task task) {
        System.out.println("开始删除任务: " + task.getName() + " ID: " + task.getId() + " completed=" + task.isCompleted());
        
        // 标记任务为已删除
        task.setDeleted(true);
        task.setLastModified(System.currentTimeMillis());
        
        // 从所有列表中移除任务
        if (task.isCompleted()) {
            System.out.println("从已完成列表移除任务: " + task.getName());
            completedTasks.remove(task);
        } else {
            System.out.println("从未完成列表移除任务: " + task.getName());
            uncompletedTasks.remove(task);
        }
        
        // 从所属清单中移除（如果有）
        if (task.getBelongsTo() != null) {
            System.out.println("从清单移除任务: " + task.getName() + " 清单: " + task.getBelongsTo().getName());
            task.getBelongsTo().getTasks().remove(task);
            if (!changedLists.contains(task.getBelongsTo())) {
                changedLists.add(task.getBelongsTo());
            }
        }
        
        // 添加到已删除任务列表
        if (!deletedTasks.contains(task)) {
            deletedTasks.add(task);
            System.out.println("任务已添加到删除列表: " + task.getName() + " ID: " + task.getId() + " completed=" + task.isCompleted());
        }
        
        // 刷新界面
        refreshCurrentView();
        
        // 标记数据已修改并同步
        markDataAsDirty();
        syncToServer();
        
        System.out.println("当前已删除任务数: " + deletedTasks.size());
    }
    private void showNewListDialog() {
        // 创建对话框
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("新建清单");
        dialog.setHeaderText("创建新的任务清单");
        // 移除默认图标
        dialog.setGraphic(null);
        // 创建界面组件
        TextField listNameField = new TextField();
        listNameField.setPromptText("请输入清单名称");
        ComboBox<String> colorComboBox = new ComboBox<>();
        ObservableList<String> colors = FXCollections.observableArrayList(
                "black", "red", "blue", "green", "orange"
        );
        colorComboBox.setItems(colors);
        colorComboBox.setValue("black"); // 默认颜色
        DatePicker datePicker = new DatePicker();
        datePicker.setValue(LocalDate.now());
        // 创建布局并添加组件
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 20, 20));
        grid.add(new Label("清单名称:"), 0, 0);
        grid.add(listNameField, 1, 0);
        grid.add(new Label("选择颜色:"), 0, 1);
        grid.add(colorComboBox, 1, 1);
        grid.add(new Label("选择日期:"), 0, 2);
        grid.add(datePicker, 1, 2);
        dialog.getDialogPane().setContent(grid);
        // 添加确定和取消按钮
        ButtonType okButtonType = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButtonType = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, cancelButtonType);
        // 配置按钮样式
        Button okButton = (Button) dialog.getDialogPane().lookupButton(okButtonType);
        okButton.setStyle("-fx-background-color: lightblue;");
        Button cancelButton = (Button) dialog.getDialogPane().lookupButton(cancelButtonType);
        cancelButton.setStyle("-fx-background-color: lightgray;");
        // 处理确定按钮点击事件
        dialog.setResultConverter(buttonType -> {
            if (buttonType == okButtonType) {
                String name = listNameField.getText().trim();
                if (!name.isEmpty()) {
                    // 检查名称是否为空或已存在
                    if (TaskList.isNameExists(customLists, name)) {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("警告");
                        alert.setHeaderText(null);
                        alert.setContentText("清单名称已存在，请使用其他名称。");
                        alert.showAndWait();
                        return null;
                    }
                    String color = colorComboBox.getValue();
                    LocalDate date = datePicker.getValue();
                    TaskList newList = new TaskList(name);
                    customLists.add(newList);
                    addChangedList(newList);
                    refreshCustomListsUI();
                    markDataAsDirty();
                    syncToServer();
                } else {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("警告");
                    alert.setHeaderText(null);
                    alert.setContentText("清单名称不能为空。");
                    alert.showAndWait();
                }
            }
            return buttonType;
        });
        // 显示对话框
        dialog.showAndWait();
    }
    private void addTaskToCurrentView(String taskName) {
        // 检查任务名称是否为空
        if (taskName.isEmpty()) {
            showAlert("错误", "任务名称不能为空");
            return;
        }
        ObservableList<Task> targetTasks;
        TaskList currentList = null;
        // 确定任务添加到哪个列表
        if (currentView.equals("今天") || currentView.equals("收集箱") || currentView.equals("已完成")) {
            // 全局任务列表
            targetTasks = uncompletedTasks;
        } else {
            // 自定义清单
            currentList = customLists.stream()
                    .filter(list -> list.getName().equals(currentView))
                    .findFirst().orElse(null);
            if (currentList == null) {
                showAlert("错误", "无法找到目标清单");
                return;
            }
            targetTasks = currentList.getTasks();
        }
        if (Task.isNameExists(targetTasks, taskName)) {
            showAlert("错误", "任务名称已存在，请使用不同的名称");
            return;
        }
        Task newTask = new Task(taskName);
        if (currentList != null) {
            newTask.setBelongsTo(currentList);
            currentList.getTasks().add(newTask);
            if (!changedLists.contains(currentList)) changedLists.add(currentList);
        } else {
            uncompletedTasks.add(newTask);
        }
        if (!changedTasks.contains(newTask)) changedTasks.add(newTask);
        taskInput.clear();
        refreshCurrentView();
        markDataAsDirty();
        syncToServer();
    }
    private void setupTaskInput() {
        taskInput.setOnAction(e -> {
            String taskName = taskInput.getText().trim();
            addTaskToCurrentView(taskName);
        });
    }
    private void setupPeriodicSync() {
        if (!syncEnabled) return;
        ScheduledExecutorService syncService = Executors.newSingleThreadScheduledExecutor();
        syncService.scheduleAtFixedRate(
            () -> {
                if (isDataDirty) {
                    syncWithServer();
                }
            },
            3, // 初始延迟30秒
            3, // 每30秒同步一次
            TimeUnit.SECONDS
        );
    }
    private void syncWithServer() {
        if (!syncEnabled) return;
        TaskSyncData syncData = new TaskSyncData(
            new ArrayList<Task>(uncompletedTasks),
            new ArrayList<>(deletedTasks),
            new ArrayList<com.todoapp.shared.TaskList>(customLists)
        );
        networkService.syncTasks(syncData)
            .thenAccept(v -> {
                Platform.runLater(() -> {
                    System.out.println("数据已同步到服务器");
                    isDataDirty = false;
                    updateStatusLabel();
                });
            })
            .exceptionally(e -> {
                Platform.runLater(() -> {
                    showAlert("同步失败", "无法同步数据到服务器: " + e.getMessage());
                });
                return null;
            });
    }
    private void handleNetworkData(TaskSyncData data) {
        // 更新本地数据
        updateLocalData(data);
        // 更新同步状态
        syncStatus.set("上次同步: " + LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("HH:mm:ss")
        ));
        isOnline.set(true);
    }
    private void handleNetworkError(Throwable error) {
        isOnline.set(false);
        syncStatus.set("同步失败: " + error.getMessage());
        // 显示错误提示
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("同步错误");
            alert.setHeaderText("无法与服务器同步");
            alert.setContentText(error.getMessage());
            alert.showAndWait();
        });
    }
    private void updateLocalData(TaskSyncData data) {
        Platform.runLater(() -> {
            // 先恢复所有Task的JavaFX属性和监听器
            for (Task t : data.getTasks()) {
                t.restoreListener(this);
            }
            // 更新任务列表
            uncompletedTasks.setAll(data.getTasks().stream()
                .filter(task -> !task.isCompleted())
                .collect(java.util.stream.Collectors.toList()));
            completedTasks.setAll(data.getTasks().stream()
                .filter(Task::isCompleted)
                .collect(java.util.stream.Collectors.toList()));
            customLists.setAll(data.getCustomLists());
            // 刷新显示
            refreshCurrentView();
            refreshCustomListsUI();
        });
    }
    // 在添加/修改/删除任务或清单时同步到服务器
    private void syncToServer() {
        if (!syncEnabled) return;
        
        // 收集所有任务，包括自定义清单中的任务
        ArrayList<Task> allTasks = new ArrayList<>();
        allTasks.addAll(uncompletedTasks);
        allTasks.addAll(completedTasks);
        
        // 添加自定义清单中的任务
        for (TaskList list : customLists) {
            for (Task task : list.getTasks()) {
                if (!allTasks.contains(task)) {
                    allTasks.add(task);
                }
            }
        }
        
        TaskSyncData delta = new TaskSyncData(
            allTasks,
            new ArrayList<>(deletedTasks),
            new ArrayList<>(customLists)
        );
        
        System.out.println("syncToServer ALL tasks: " + allTasks.size());
        System.out.println("syncToServer Deleted tasks: " + deletedTasks.size());
        
        networkService.syncTasks(delta)
            .thenAccept(v -> {
                changedTasks.clear();
                changedLists.clear();
                deletedTasks.clear();
                Platform.runLater(this::refreshCurrentView);
            })
            .exceptionally(error -> {
                handleNetworkError(error);
                Platform.runLater(this::refreshCurrentView);
                return null;
            });
    }
    // 在窗口关闭时关闭网络服务
    public void shutdown() {
        if (networkService != null) {
            networkService.shutdown();
        }
    }
    // 任务状态变更时，加入变更集
    // 在Task.java的setCompleted等方法里调用MainBorderPane的addChangedTask(this)
    public void addChangedTask(Task task) {
        if (!changedTasks.contains(task)) changedTasks.add(task);
    }
    public void addChangedList(TaskList list) {
        if (!changedLists.contains(list)) changedLists.add(list);
    }
    public static void main(String[] args) {
        launch(args);
    }
}