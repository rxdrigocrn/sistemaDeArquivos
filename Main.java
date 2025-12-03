
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sistema de arquivos virtual em um único arquivo (.vfs) com journaling (write-ahead).
 *
 * Uso: javac Main.java && java Main shell virtualfs.vfs
 * Comandos:
 *   mkdir <path>
 *   rmdir <path> [-r]
 *   touch <path> ["conteúdo"]
 *   ls [<path>]
 *   cp <src> <dest>
 *   rm <path>
 *   mv <src> <dest|novonome>
 *   cat <file>
 *   exit
 */

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length >= 1 && "shell".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                System.out.println("Uso: java Main shell <virtual_fs_file.vfs>");
                return;
            }
            Path vfsPath = Path.of(args[1]);
            FileSystemSimulator fs = new FileSystemSimulator(vfsPath);
            Shell shell = new Shell(fs);
            shell.run();
        } else {
            System.out.println("Modo esperado: 'shell <virtual_fs_file.vfs>'");
        }
    }

     public static abstract class FSNode implements Serializable {

        private static final long serialVersionUID = 1L;
        protected String name;
        protected long createdAt;
        protected long modifiedAt;
        protected FSDirectory parent;

        public FSNode(String name) {
            this.name = name;
            this.createdAt = System.currentTimeMillis();
            this.modifiedAt = createdAt;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
            this.modifiedAt = System.currentTimeMillis();
        }

        public String getPath() {
            if (parent == null) {
                return "/";
            }
            String p = parent.getPath();
            if ("/".equals(p)) {
                return "/" + name;
            }
            return p + "/" + name;
        }
    }

    public static class FSFile extends FSNode {

        private static final long serialVersionUID = 1L;
        private byte[] content;

        public FSFile(String name, byte[] content) {
            super(name);
            this.content = content == null ? new byte[0] : content;
        }

        public int getSize() {
            return content.length;
        }

        public byte[] read() {
            return content;
        }

        public void write(byte[] data) {
            this.content = data == null ? new byte[0] : data;
            this.modifiedAt = System.currentTimeMillis();
        }
    }

    public static class FSDirectory extends FSNode {

        private static final long serialVersionUID = 1L;
        private LinkedHashMap<String, FSNode> children = new LinkedHashMap<>();

        public FSDirectory(String name) {
            super(name);
        }

        public Collection<FSNode> getChildren() {
            return children.values();
        }

        public FSNode getChild(String name) {
            return children.get(name);
        }

        public void add(FSNode node) {
            node.parent = this;
            children.put(node.getName(), node);
            this.modifiedAt = System.currentTimeMillis();
        }

        public void remove(String name) {
            children.remove(name);
            this.modifiedAt = System.currentTimeMillis();
        }

        public boolean isEmpty() {
            return children.isEmpty();
        }
    }

    public static class Operation implements Serializable {

        private static final long serialVersionUID = 1L;

        public enum Type {
            CREATE_FILE, DELETE_FILE, COPY_FILE, RENAME_FILE, CREATE_DIR, DELETE_DIR, RENAME_DIR
        }
        public long id;
        public Type type;
        public String path;
        public String destPath;
        public String payloadBase64;
        public String state;

        public Operation() {
        }

        public Operation(long id, Type type, String path) {
            this.id = id;
            this.type = type;
            this.path = path;
            this.state = "PENDING";
        }

        public String toString() {
            return id + " " + type + " " + path + (destPath != null ? " -> " + destPath : "") + " [" + state + "]";
        }
    }

    public static class FileSystemSimulator implements Serializable {

        private static final long serialVersionUID = 1L;

        private FSDirectory root;
        private ArrayList<Operation> journal;

        private transient Path vfsFile;
        private transient AtomicLong nextId = new AtomicLong(1);

        public FileSystemSimulator(Path vfsFile) throws IOException, ClassNotFoundException {
            this.vfsFile = vfsFile;
            if (Files.exists(vfsFile)) {
                try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(vfsFile)))) {
                    FileSystemSimulator loaded = (FileSystemSimulator) ois.readObject();
                    this.root = loaded.root;
                    this.journal = loaded.journal != null ? loaded.journal : new ArrayList<>();
                }
            } else {
                this.root = new FSDirectory("");
                this.journal = new ArrayList<>();
                persist();
            }
            long max = 0;
            for (Operation o : journal) {
                if (o != null) {
                    max = Math.max(max, o.id);
                }
            }
            nextId.set(max + 1);

            recover();
        }

        private synchronized Operation recordAndApply(Operation.Type type, String path, String dest, String payloadBase64) throws Exception {
            Operation op = new Operation(nextId.getAndIncrement(), type, path);
            op.destPath = dest;
            op.payloadBase64 = payloadBase64;
            op.state = "PENDING";

            journal.add(op);
            persist();

            applyOperation(op);

            op.state = "COMMITTED";
            persist();
            return op;
        }

        private synchronized void persist() {
            try {
                Path tmp = vfsFile.resolveSibling(vfsFile.getFileName().toString() + ".tmp");
                try (ObjectOutputStream oos = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                    oos.writeObject(this);
                }
                Files.move(tmp, vfsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                System.out.println("Erro ao persistir virtual fs: " + e.getMessage());
            }
        }

        private synchronized void recover() {
            List<Operation> pend = new ArrayList<>();
            for (Operation o : journal) {
                if ("PENDING".equals(o.state)) {
                    pend.add(o);
                }
            }
            if (pend.isEmpty()) {
                return;
            }
            System.out.println("Recuperando " + pend.size() + " operações pendentes...");
            for (Operation o : pend) {
                try {
                    applyOperation(o);
                    o.state = "COMMITTED";
                } catch (Exception ex) {
                    System.out.println("Falha ao reaplicar op " + o.id + ": " + ex.getMessage());
                }
            }
            persist();
        }

        private FSNode traverse(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return root;
            }
            String p = path.startsWith("/") ? path.substring(1) : path;
            if (p.isEmpty()) {
                return root;
            }
            String[] parts = p.split("/");
            FSDirectory cur = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) {
                    continue;
                }
                FSNode child = cur.getChild(part);
                if (child == null) {
                    return null;
                }
                if (i == parts.length - 1) {
                    return child;
                }
                if (child instanceof FSDirectory) {
                    cur = (FSDirectory) child;
                } else {
                    return null;
                }
            }
            return cur;
        }

        private FSDirectory traverseParentDir(String path) {
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            String parentPath = idx <= 0 ? "/" : path.substring(0, idx);
            FSNode parentNode = traverse(parentPath);
            if (parentNode instanceof FSDirectory) {
                return (FSDirectory) parentNode;
            }
            return null;
        }

        private String buildSiblingPath(String path, String newName) {
            int idx = path.lastIndexOf('/');
            String parent = idx <= 0 ? "/" : path.substring(0, idx);
            if ("/".equals(parent)) {
                return "/" + newName;
            }
            return parent + "/" + newName;
        }

        // FUNÇÕES BÁSICAS
        public synchronized void createFile(String path, byte[] content) throws Exception {
            String base64 = Base64.getEncoder().encodeToString(content == null ? new byte[0] : content);
            recordAndApply(Operation.Type.CREATE_FILE, path, null, base64);
        }

        public synchronized void copyFile(String src, String dest) throws Exception {
            recordAndApply(Operation.Type.COPY_FILE, src, dest, null);
        }

        public synchronized void deleteFile(String path) throws Exception {
            recordAndApply(Operation.Type.DELETE_FILE, path, null, null);
        }

        public synchronized void renameFile(String path, String newNameOrDestPath) throws Exception {
            String dest = newNameOrDestPath.contains("/") ? newNameOrDestPath : buildSiblingPath(path, newNameOrDestPath);
            recordAndApply(Operation.Type.RENAME_FILE, path, dest, null);
        }

        public synchronized void createDirectory(String path) throws Exception {
            recordAndApply(Operation.Type.CREATE_DIR, path, null, null);
        }

        public synchronized void deleteDirectory(String path, boolean recursive) throws Exception {
            if (!recursive) {
                FSNode node = traverse(path);
                if (!(node instanceof FSDirectory)) {
                    throw new Exception("Diretório não existe: " + path);
                }
                FSDirectory dir = (FSDirectory) node;
                if (!dir.isEmpty()) {
                    throw new Exception("Diretório não vazio: " + path);
                }
            }
            recordAndApply(Operation.Type.DELETE_DIR, path, null, recursive ? "-r" : null);
        }

        public synchronized void renameDirectory(String path, String newNameOrDest) throws Exception {
            String dest = newNameOrDest.contains("/") ? newNameOrDest : buildSiblingPath(path, newNameOrDest);
            recordAndApply(Operation.Type.RENAME_DIR, path, dest, null);
        }

        public synchronized List<String> list(String path) throws Exception {
            FSNode node = traverse(path);
            if (node == null) {
                throw new Exception("Caminho não encontrado: " + path);
            }
            if (node instanceof FSDirectory) {
                List<String> out = new ArrayList<>();
                for (FSNode c : ((FSDirectory) node).getChildren()) {
                    String type = c instanceof FSDirectory ? "d" : "f";
                    out.add(type + "\t" + c.getName());
                }
                return out;
            } else {
                throw new Exception("Caminho não é um diretório: " + path);
            }
        }

        public synchronized String readFileAsString(String path) throws Exception {
            FSNode node = traverse(path);
            if (!(node instanceof FSFile)) {
                throw new Exception("Não é arquivo: " + path);
            }
            byte[] data = ((FSFile) node).read();
            return new String(data);
        }

        private void applyOperation(Operation op) throws Exception {
            switch (op.type) {
                case CREATE_FILE: {
                    FSDirectory parent = traverseParentDir(op.path);
                    if (parent == null) {
                        throw new Exception("Diretório pai não existe: " + op.path);
                    }
                    String name = op.path.substring(op.path.lastIndexOf('/') + 1);
                    if (parent.getChild(name) != null) {
                        throw new Exception("Arquivo/diretório já existe: " + name);
                    }
                    byte[] content = op.payloadBase64 == null ? new byte[0] : Base64.getDecoder().decode(op.payloadBase64);
                    FSFile f = new FSFile(name, content);
                    parent.add(f);
                    break;
                }
                case COPY_FILE: {
                    FSNode src = traverse(op.path);
                    if (!(src instanceof FSFile)) {
                        throw new Exception("Fonte não é arquivo: " + op.path);
                    }
                    FSDirectory destParent = traverseParentDir(op.destPath);
                    if (destParent == null) {
                        throw new Exception("Diretório destino não existe: " + op.destPath);
                    }
                    String destName = op.destPath.substring(op.destPath.lastIndexOf('/') + 1);
                    if (destParent.getChild(destName) != null) {
                        throw new Exception("Destino já existe: " + op.destPath);
                    }
                    FSFile newf = new FSFile(destName, ((FSFile) src).read());
                    destParent.add(newf);
                    break;
                }
                case DELETE_FILE: {
                    FSNode node = traverse(op.path);
                    if (!(node instanceof FSFile)) {
                        throw new Exception("Não é arquivo: " + op.path);
                    }
                    FSDirectory parent = node.parent;
                    parent.remove(node.getName());
                    break;
                }
                case RENAME_FILE: {
                    FSNode node = traverse(op.path);
                    if (node == null) {
                        throw new Exception("Não encontrado: " + op.path);
                    }
                    String newName = op.destPath.substring(op.destPath.lastIndexOf('/') + 1);
                    FSDirectory parent = node.parent;
                    parent.remove(node.getName());
                    node.setName(newName);
                    parent.add(node);
                    break;
                }
                case CREATE_DIR: {
                    FSDirectory parent = traverseParentDir(op.path);
                    if (parent == null) {
                        throw new Exception("Diretório pai não existe: " + op.path);
                    }
                    String name = op.path.substring(op.path.lastIndexOf('/') + 1);
                    if (parent.getChild(name) != null) {
                        throw new Exception("Já existe: " + name);
                    }
                    FSDirectory d = new FSDirectory(name);
                    parent.add(d);
                    break;
                }
                case DELETE_DIR: {
                    FSNode node = traverse(op.path);
                    if (!(node instanceof FSDirectory)) {
                        throw new Exception("Não é diretório: " + op.path);
                    }
                    FSDirectory dir = (FSDirectory) node;
                    if (!dir.isEmpty() && (op.payloadBase64 == null || !"-r".equals(op.payloadBase64))) {
                        throw new Exception("Dir não vazio. Use recursive flag.");
                    }
                    dir.parent.remove(dir.getName());
                    break;
                }
                case RENAME_DIR: {
                    FSNode node = traverse(op.path);
                    if (!(node instanceof FSDirectory)) {
                        throw new Exception("Não é diretório: " + op.path);
                    }
                    String newName = op.destPath.substring(op.destPath.lastIndexOf('/') + 1);
                    FSDirectory parent = node.parent;
                    parent.remove(node.getName());
                    node.setName(newName);
                    parent.add(node);
                    break;
                }
                default:
                    throw new Exception("Tipo de operação desconhecido: " + op.type);
            }
        }
    }

    // SHELL
    public static class Shell {

        private final FileSystemSimulator fs;
        private final Scanner in = new Scanner(System.in);

        public Shell(FileSystemSimulator fs) {
            this.fs = fs;
        }

        public void run() {
            System.out.println("VirtualFS (single-file) - shell. 'help' for commands.");
            while (true) {
                System.out.print("vfs> ");
                String line;
                try {
                    line = in.nextLine();
                } catch (NoSuchElementException ex) {
                    break;
                }
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = splitArgs(line);
                String cmd = parts[0];
                try {
                    switch (cmd) {
                        case "exit":
                            return;
                        case "help":
                            printHelp();
                            break;
                        case "mkdir":
                            fs.createDirectory(parts[1]);
                            System.out.println("OK");
                            break;
                        case "rmdir":
                            boolean rec = parts.length > 2 && "-r".equals(parts[2]);
                            fs.deleteDirectory(parts[1], rec);
                            System.out.println("OK");
                            break;
                        case "touch":
                            String content = parts.length > 2 ? parts[2] : "";
                            fs.createFile(parts[1], content.getBytes());
                            System.out.println("OK");
                            break;
                        case "ls":
                            List<String> items = fs.list(parts.length > 1 ? parts[1] : "/");
                            for (String it : items) {
                                System.out.println(it);
                            }
                            break;
                        case "cp":
                            fs.copyFile(parts[1], parts[2]);
                            System.out.println("OK");
                            break;
                        case "rm":
                            fs.deleteFile(parts[1]);
                            System.out.println("OK");
                            break;
                        case "mv":
                            if (parts[2].contains("/")) {
                                fs.renameFile(parts[1], parts[2]);
                            } else {
                                fs.renameFile(parts[1], fs.buildSiblingPath(parts[1], parts[2]));
                            }
                            System.out.println("OK");
                            break;
                        case "cat":
                            String txt = fs.readFileAsString(parts[1]);
                            System.out.println(txt);
                            break;
                        default:
                            System.out.println("Comando desconhecido: " + cmd);
                    }
                } catch (Exception e) {
                    System.out.println("Erro: " + e.getMessage());
                }
            }
        }

        private void printHelp() {
            System.out.println("Comandos:\n  mkdir <path>\n  rmdir <path> [-r]\n  touch <path> [\"content\"]\n  ls [<path>]\n  cp <src> <dest>\n  rm <path>\n  mv <src> <dest|newname>\n  cat <file>\n  exit\n");
        }

        private String[] splitArgs(String line) {
            List<String> out = new ArrayList<>();
            boolean inQuotes = false;
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                    continue;
                }
                if (c == ' ' && !inQuotes) {
                    if (cur.length() > 0) {
                        out.add(cur.toString());
                        cur.setLength(0);
                    }
                    continue;
                }
                cur.append(c);
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
            return out.toArray(new String[0]);
        }
    }
}
