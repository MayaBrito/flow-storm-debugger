.PHONY: clean docs test lint-dbg lint-inst install-dbg install-inst deploy-dbg deploy-inst

docs: docs/user_guide.adoc
	asciidoctorj -b html5 -o docs/user_guide.html docs/user_guide.adoc
clean:
	clj -T:build clean

test:
	clj -M:test:dev unit-clj

lint:
	clj-kondo --config .clj-kondo/config.edn --lint src-dbg src-shared src-inst

connect-to-shadow:
	clj -X flow-storm.debugger.main/start-debugger :port 9000 :repl-type :shadow :build-id :browser-repl

connect-to-clj:
	clj -X flow-storm.debugger.main/start-debugger :port 9000

test-instrument-own-cljs-light:
	clj -X:test flow-storm.api/cli-run :instrument-ns '#{"cljs."}' :profile ':light' :flow-id 0 :require-before '#{"cljs.repl.node"}' :excluding-ns '#{"cljs.core"}' :fn-symb 'cljs.main/-main' :fn-args '["-t" "nodejs" "/home/jmonetta/flow-storm-testers/cljs/src/org/foo/myscript.cljs"]';

test-instrument-own-cljs-full:
	clj -X:test flow-storm.api/cli-run :instrument-ns '#{"cljs."}' :profile ':full'  :flow-id 0 :require-before '#{"cljs.repl.node"}' :excluding-ns '#{"cljs.core"}' :fn-symb 'cljs.main/-main' :fn-args '["-t" "nodejs" "/home/jmonetta/flow-storm-testers/cljs/src/org/foo/myscript.cljs"]';

test-local:
	clj -X:dev dev/start-and-add-data

test-remote:
	clj -X:dev dev/run-remote-test

flow-storm-dbg.jar:
	clj -T:build jar-dbg

flow-storm-inst.jar:
	clj -T:build jar-inst

install-dbg: flow-storm-dbg.jar
	mvn install:install-file -Dfile=target/flow-storm-dbg.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-dbg/pom.xml

install-inst: flow-storm-inst.jar
	mvn install:install-file -Dfile=target/flow-storm-inst.jar -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-inst/pom.xml

deploy-dbg:
	mvn deploy:deploy-file -Dfile=target/flow-storm-dbg.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-dbg/pom.xml -Durl=https://clojars.org/repo

deploy-inst:
	mvn deploy:deploy-file -Dfile=target/flow-storm-inst.jar -DrepositoryId=clojars -DpomFile=target/classes/META-INF/maven/com.github.flow-storm/flow-storm-inst/pom.xml -Durl=https://clojars.org/repo
