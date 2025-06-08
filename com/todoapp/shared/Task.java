package com.todoapp.shared;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.ObservableList;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.UUID;
public class Task implements Serializable {
    private static final long serialVersionUID = 1L;
    private String id; // 唯一ID
    private String name;
    boolean completed;
    private boolean deleted; // 删除标记
    private long lastModified; // 时间戳
    // transient JavaFX属性
    private transient StringProperty nameProperty;
    private transient BooleanProperty completedProperty;
    private transient ChangeListener<Boolean> completionListener;
    // 任务所属的清单（null表示全局任务）
    private TaskList belongsTo;
    // 检查任务名称是否已存在于清单中
    public static boolean isNameExists(ObservableList<Task> tasks, String name) {
        return tasks.stream()
                .anyMatch(task -> task.getName().equalsIgnoreCase(name));
    }
    public Task(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.completed = false;
        this.deleted = false;
        this.lastModified = System.currentTimeMillis();
        restoreFX();
    }

    public void restoreFX() {
        if (nameProperty == null) nameProperty = new SimpleStringProperty(name);
        if (completedProperty == null) completedProperty = new SimpleBooleanProperty(completed);
        nameProperty.addListener((obs, oldVal, newVal) -> {
            this.name = newVal;
            this.lastModified = System.currentTimeMillis();
        });
        completedProperty.addListener((obs, oldVal, newVal) -> {
            this.completed = newVal;
            this.lastModified = System.currentTimeMillis();
        });
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isCompleted() { return completed; }
    public boolean isDeleted() { return deleted; }
    public long getLastModified() { return lastModified; }
    public void setName(String name) {
        this.name = name;
        if (nameProperty != null) nameProperty.set(name);
        this.lastModified = System.currentTimeMillis();
        notifyChanged();
    }
    public void setCompleted(boolean completed) {
        this.completed = completed;
        if (completedProperty != null) completedProperty.set(completed);
        this.lastModified = System.currentTimeMillis();
        System.out.println("setCompleted: " + this.getName() + " -> " + completed + " lastModified=" + lastModified);
        MainBorderPane main = MainBorderPane.getInstance();
        if (main != null) main.addChangedTask(this);
        notifyChanged();
    }
    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
        this.lastModified = System.currentTimeMillis();
        notifyChanged();
    }
    public void setLastModified(long ts) { this.lastModified = ts; }
    public StringProperty nameProperty() { if (nameProperty == null) restoreFX(); return nameProperty; }
    public BooleanProperty completedProperty() { if (completedProperty == null) restoreFX(); return completedProperty; }
    // 设置和获取所属清单
    public void setBelongsTo(TaskList list) {
        this.belongsTo = list;
    }
    public TaskList getBelongsTo() {
        return belongsTo;
    }
    // 自定义序列化方法
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeObject(name);
        out.writeBoolean(completed);
        out.writeBoolean(deleted);
        out.writeLong(lastModified);
    }
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        name = (String) in.readObject();
        completed = in.readBoolean();
        deleted = in.readBoolean();
        lastModified = in.readLong();
        restoreFX();
    }
    public void restoreListener(MainBorderPane mainApp) {
        // 如果属性为null，先初始化
        if (this.nameProperty == null) {
            this.nameProperty = new SimpleStringProperty(this.getName());
        }
        if (this.completedProperty == null) {
            // 尝试用已有get方法获取原始状态，默认为false
            this.completedProperty = new SimpleBooleanProperty(this.isCompleted());
        }
        // 移除旧监听器（避免重复）
        if (completionListener != null) {
            this.completedProperty.removeListener(completionListener);
        }
        // 添加新监听器
        completionListener = (obs, oldVal, newVal) -> {
            if (newVal) {
                mainApp.completedTasks.add(this);
                this.completed = true;
                mainApp.uncompletedTasks.remove(this);
            } else {
                mainApp.uncompletedTasks.add(this);
                this.completed = false;
                mainApp.completedTasks.remove(this);
            }
            mainApp.refreshCurrentView();
            mainApp.markDataAsDirty();
        };
        this.completedProperty.addListener(completionListener);
    }
    private void notifyChanged() {
        MainBorderPane main = MainBorderPane.getInstance();
        if (main != null) main.addChangedTask(this);
    }
}