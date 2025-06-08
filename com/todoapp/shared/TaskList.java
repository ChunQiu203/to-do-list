package com.todoapp.shared;

import java.io.*;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.UUID;
public class TaskList implements Serializable {
    private static final long serialVersionUID = 1L;
    private String id;
    private String name;
    private boolean deleted;
    private long lastModified;
    private transient ObservableList<Task> tasks;
    public TaskList(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.deleted = false;
        this.lastModified = System.currentTimeMillis();
        this.tasks = FXCollections.observableArrayList();
    }
    public void restoreFX() {
        if (tasks == null) tasks = FXCollections.observableArrayList();
        for (Task t : tasks) {
            t.setBelongsTo(this);
        }
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isDeleted() { return deleted; }
    public long getLastModified() { return lastModified; }
    public ObservableList<Task> getTasks() { if (tasks == null) restoreFX(); return tasks; }
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        if (tasks == null) tasks = FXCollections.observableArrayList();
        out.writeInt(tasks.size());
        for (Task task : tasks) {
            out.writeObject(task);
        }
    }
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        int size = in.readInt();
        tasks = FXCollections.observableArrayList();
        for (int i = 0; i < size; i++) {
            Task t = (Task) in.readObject();
            t.setBelongsTo(this);
            tasks.add(t);
        }
    }
    public static boolean isNameExists(ObservableList<TaskList> lists, String name) {
        return lists.stream().anyMatch(list -> list.getName().equals(name));
    }
}