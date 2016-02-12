(ns puppetlabs.puppetserver.shell-utils
  (:require [schema.core :as schema]
            [clojure.java.io :as io]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.string :as string])
  (:import (com.puppetlabs.puppetserver ShellUtils)
           (java.io IOException InputStream OutputStream)
           (org.apache.commons.io IOUtils)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def ExecutionResult
  "A map that contains the details of the result of executing a command."
  {:exit-code schema/Int
   :stderr schema/Str
   :stdout schema/Str})

(def ExecutionResultStreamed
  "A map that contains the details of the result of executing a command with
  stdout as a stream."
  {:exit-code schema/Int
   :stderr schema/Str
   :stdout InputStream})

(def ExecutionOptions
  {(schema/optional-key :args) [schema/Str]
   (schema/optional-key :env) (schema/maybe {schema/Str schema/Str})
   (schema/optional-key :in) (schema/maybe InputStream)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Internal

(schema/defn ^:private ^:always-validate
  validate-command!
  "Checks the command string to ensure that it is an absolute path, executable
  and that the file exists. An exception is thrown if any of those are not the
  case."
  [command :- schema/Str]
  (let [command-file (io/as-file command)]
    (cond
      (not (.isAbsolute command-file))
      (throw (IllegalArgumentException.
              (format "An absolute path is required, but '%s' is not an absolute path" command)))
      (not (.exists command-file))
      (let [cmds (string/split command #" ")]
        (if (and (> (count cmds) 1) (.exists (io/as-file (first cmds))))
          (throw (IllegalArgumentException.
                  (format "Command '%s' appears to use command-line arguments, but this is not allowed." command)))
          (throw (IllegalArgumentException.
                  (format "The referenced command '%s' does not exist" command)))))
      (not (.canExecute command-file))
      (throw (IllegalArgumentException.
              (format "The referenced command '%s' is not executable" command))))))

(def default-execution-options
  {:args []
   :env nil
   :in nil})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  execute-command-streamed :- ExecutionResultStreamed
  "Execute the specified fully qualified command (string) and any included
  command-arguments (vector of strings) and return the exit-code (integer),
  and the contents of the stdout (stream) and stderr (string) for the command."
  ([command :- schema/Str]
   (execute-command-streamed command {}))
  ([command :- schema/Str
    opts :- ExecutionOptions]
   (let [{:keys [args env in]} (merge default-execution-options opts)]
     (validate-command! command)
     (let [process (ShellUtils/executeCommand
                    command
                    (into-array String args)
                    (if env
                      (ks/mapkeys name env))
                    in)]
       {:exit-code (.getExitCode process)
        :stderr (.getError process)
        :stdout (.getOutputAsStream process)}))))

(schema/defn ^:always-validate
  execute-command :- ExecutionResult
  "Execute the specified fully qualified command (string) and any included
  command-arguments (vector of strings) and return the exit-code (integer),
  and the contents of the stdout (string) and stderr (string) for the command."
  ([command :- schema/Str]
   (execute-command command {}))
  ([command :- schema/Str
    opts :- ExecutionOptions]
   (let [result (execute-command-streamed command opts)]
     (update-in result [:stdout]
                (fn [stdout] (IOUtils/toString stdout "UTF-8"))))))
