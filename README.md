# 📁 Simulador de Sistema de Arquivos com Journaling  
Projeto da disciplina de Sistemas Operacionais — Java

link do github - https://github.com/rxdrigocrn/sistemaDeArquivos

Alunos:
- Rodrigo Cirino
- Erfon Spanos

## 📌 Metodologia

O simulador foi desenvolvido em **Java**, utilizando uma arquitetura baseada em classes para representar arquivos, diretórios e o sistema de arquivos.  
A execução do sistema ocorre via **chamadas de métodos**, simulando comandos reais de um SO.

Cada funcionalidade (criar, apagar, renomear, copiar, listar, etc.) é mapeada para um método dentro da classe principal do simulador.

Foi adotado também um **modo avançado (Shell)** que executa o sistema com suporte a **journaling**, garantindo integridade em caso de falhas.

---

# 🧩 Parte 1: Introdução ao Sistema de Arquivos com Journaling

## 📂 O que é um Sistema de Arquivos?

Um **sistema de arquivos** é a estrutura usada pelo sistema operacional para organizar, armazenar e recuperar dados em um dispositivo físico. Ele define:

- Como os arquivos são representados  
- Como os diretórios são estruturados  
- Como os dados são gravados, apagados e acessados  

Sem um sistema de arquivos, seria impossível organizar dados de forma eficiente e segura.

## 📝 O que é Journaling?

**Journaling** é uma técnica usada por sistemas de arquivos modernos (como EXT3, NTFS, XFS, JFS) para aumentar a integridade dos dados.

### ✔️ Propósito do Journaling
- Evitar corrupção de dados em caso de falha  
- Permitir recuperação rápida após interrupção  
- Registrar todas as operações antes de aplicá-las  

### ✔️ Como funciona?

Funciona através de um **log de transações** onde as operações são registradas antes de serem executadas definitivamente.  
Esse log pode registrar:

- Metadados (nome, tamanho, estrutura dos diretórios)  
- Dados completos  
- Ou ambos  

### ✔️ Tipos de Journaling

| Tipo | Descrição |
|------|-----------|
| **Write-Ahead Logging (WAL)** | Registra as operações no log antes de modificarem os dados reais. |
| **Metadata Journaling** | Apenas metadados são registrados. |
| **Full Journaling** | Tanto dados quanto metadados são registrados. |
| **Log-Structured File System** | Todo o disco é tratado como um log contínuo. |

Este simulador segue o modelo **write-ahead**, onde toda operação é registrada no log antes de ser aplicada.

---

# 🏗️ Parte 2: Arquitetura do Simulador

## 📦 Estruturas de Dados

O simulador utiliza três classes principais:

### **📄 Classe File**
Representa um arquivo dentro do sistema:  
- nome  
- conteúdo  
- data de criação  
- tamanho lógico  
- referência ao diretório pai  

### **📁 Classe Directory**
Representa um diretório com:  
- nome  
- subdiretórios  
- arquivos  
- ponteiro para o diretório pai  

### **🧠 Classe FileSystemSimulator**
Responsável por implementar as operações do sistema de arquivos:

- Criar diretórios  
- Apagar diretórios  
- Renomear diretórios  
- Criar arquivos  
- Copiar arquivos  
- Remover arquivos  
- Renomear arquivos  
- Listar conteúdo de diretórios  

Esta classe também controla o objeto `Journal`.

## 🗃️ Journaling

### Estrutura do Log
O log registra:

- Tipo de operação  
- Caminho do arquivo/diretório  
- Parâmetros da operação  
- Timestamp  

Exemplo de registro no log:

```
[CREATE_FILE] /docs/arquivo.txt – 2025-12-03 10:22:15
```

### Recuperação
Ao iniciar, o simulador:

1. Lê o arquivo `.journal`  
2. Reaplica operações pendentes (se houver)  
3. Reconstrói o sistema de arquivos totalmente  

---

# ⚙️ Parte 3: Implementação em Java

A implementação é dividida nas seguintes classes:

---

## **🔹 Classe `FileSystemSimulator`**
Implementa todos os comandos:

- `createDirectory(path)`
- `deleteDirectory(path)`
- `renameDirectory(old, new)`
- `createFile(path)`
- `deleteFile(path)`
- `renameFile(old, new)`
- `copyFile(source, target)`
- `listDirectory(path)`
- `startShell(storageDirectory)`

---

## **🔹 Classe `File`**
Representa um arquivo:

```java
class File {
    private String name;
    private byte[] content;
    private Directory parent;
}
### 🔹 Classe Directory

```
Representa diretórios:

```java
class Directory {
    private String name;
    private Directory parent;
    private Map<String, Directory> subdirectories;
    private Map<String, File> files;
}
```

### 🔹 Classe Journal

Gerencia o log do sistema:

```java
class Journal {
    private FileWriter writer;

    public void log(String entry) { ... }
    public void replay(FileSystemSimulator fs) { ... }
}
```

---

# 🚀 Parte 4: Instalação e Funcionamento

## 📥 Requisitos

- Java 17+
- Git instalado

---

## 📦 Como baixar o projeto

```bash
git clone https://github.com/<seu-usuario>/<seu-repo>.git
cd <seu-repo>
```

---

## ▶️ Como rodar o simulador

### Modo Shell com Journaling

```bash
java Main shell storage.bin
```

Se o arquivo **storage.bin** ainda não existir, ele será criado internamente pelo sistema e **não deve ser acessado pelo Windows**, pois os dados são gerenciados no formato proprietário do simulador.

---

## 🖥️ Comandos disponíveis no Shell

| Comando | Função |
|--------|--------|
| `mkdir <path>` | Criar diretório |
| `rmdir <path>` | Remover diretório |
| `touch <path>` | Criar arquivo |
| `rm <path>` | Apagar arquivo |
| `mv <origem> <destino>` | Renomear arquivo ou diretório |
| `cp <origem> <destino>` | Copiar arquivo |
| `ls <path>` | Listar conteúdo |

---

## 🔄 Recuperação via Journaling

Se o simulador for interrompido inesperadamente:

1. Na próxima execução, o arquivo `.journal` é carregado  
2. Todas as operações pendentes são aplicadas novamente  
3. O sistema volta exatamente ao estado anterior  

---
