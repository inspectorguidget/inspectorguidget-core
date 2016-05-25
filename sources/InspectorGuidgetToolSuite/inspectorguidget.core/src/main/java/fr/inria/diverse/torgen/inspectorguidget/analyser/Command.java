package fr.inria.diverse.torgen.inspectorguidget.analyser;

import fr.inria.diverse.torgen.inspectorguidget.helper.ClassMethodCallFilter;
import fr.inria.diverse.torgen.inspectorguidget.helper.NonAnonymClassFilter;
import fr.inria.diverse.torgen.inspectorguidget.helper.CodeBlockPos;
import org.jetbrains.annotations.NotNull;
import spoon.reflect.code.CtCodeElement;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Command {
	private final @NotNull CtExecutable<?> executable;

	private final @NotNull CommandStatmtEntry EMPTY_CMD_ENTRY = new CommandStatmtEntry(false);

	private final @NotNull List<CommandStatmtEntry> statements;

	private final @NotNull List<CommandConditionEntry> conditions;

	public Command(final @NotNull CommandStatmtEntry stat, final @NotNull List<CommandConditionEntry> conds,
				   final @NotNull CtExecutable<?> exec) {
		super();
		statements = new ArrayList<>();
		conditions = conds;
		statements.add(stat);
		executable = exec;
	}

	public @NotNull Optional<CommandStatmtEntry> getMainStatmtEntry() {
		return statements.stream().filter(entry -> entry.isMainEntry()).findFirst();
	}

	public int getLineStart() {
		return getMainStatmtEntry().orElse(EMPTY_CMD_ENTRY).
				getStatmts().stream().map(s -> s.getPosition()).filter(p -> p!=null).mapToInt(p -> p.getLine()).min().orElse(-1);
	}

	public int getLineEnd() {
		return getMainStatmtEntry().orElse(EMPTY_CMD_ENTRY).
				getStatmts().stream().map(s -> s.getPosition()).filter(p -> p!=null).mapToInt(p -> p.getEndLine()).max().orElse(-1);
	}

	public @NotNull List<CommandConditionEntry> getConditions() {
		return Collections.unmodifiableList(conditions);
	}

	public @NotNull List<CommandStatmtEntry> getStatements() {
		return Collections.unmodifiableList(statements);
	}

	public void addAllStatements(final int index, final @NotNull Collection<CommandStatmtEntry> entries) {
		statements.addAll(index, entries);
		optimiseStatementEntries();
	}

	private void optimiseStatementEntries() {
		statements.removeAll(
			statements.parallelStream().filter(stat -> statements.parallelStream().
					filter(stat2 -> stat!=stat2 && stat2.contains(stat)).findFirst().isPresent()).
					collect(Collectors.toList())
		);
	}

	public @NotNull Set<CtCodeElement> getAllStatmts() {
		return statements.stream().map(entry -> entry.getStatmts()).flatMap(s -> s.stream()).collect(Collectors.toSet());
	}

	public @NotNull CtExecutable<?> getExecutable() {
		return executable;
	}

	/**
	 * Identifies local methods of the GUI controller that are used by some statements in the main entry.
	 * The code of the identified local methods is added to the command.
	 */
	public void extractLocalDispatchCallWithoutGUIParam() {
		getMainStatmtEntry().ifPresent(main -> {
			final CtClass<?> parent = executable.getParent(new NonAnonymClassFilter());
			final List<CtParameter<?>> params = executable.getParameters();

			// Getting all the statements that are invocation of a local method without GUI parameter.
			List<CtInvocation<?>> invoks = main.getStatmts().stream().
											map(stat -> stat.getElements(new ClassMethodCallFilter(params, parent, false))).
											flatMap(s -> s.stream()).collect(Collectors.toList());

			if(invoks.size()==1 && main.getStatmts().size()==1) {
				statements.add(new CommandStatmtEntry(true, invoks.get(0).getExecutable().getDeclaration().getBody().getStatements()));
				statements.remove(main);
			}else {
				invoks.forEach(inv -> statements.add(
						new CommandStatmtEntry(false, inv.getExecutable().getDeclaration().getBody().getStatements())));
			}
		});
	}

	public @NotNull List<CodeBlockPos> getOptimalCodeBlocks() {
		return Stream.concat(statements.stream().map(stat -> stat.getStatmts().get(0).getPosition()),
							conditions.stream().map(stat -> stat.realStatmt.getPosition())).
				map(pos -> new CodeBlockPos(pos.getCompilationUnit().getFile().toString(), pos.getLine(), pos.getEndLine())).
				collect(Collectors.groupingBy(triple -> triple.file)).values().parallelStream().
				map(triples -> triples.stream().sorted((o1, o2) -> o1.startLine < o2.startLine ? -1 : o1.startLine == o2.startLine ? 0 : 1).
				collect(Collectors.toList())).map(triples -> {
			int i = 0;
			CodeBlockPos ti;
			CodeBlockPos tj;

			while(i < triples.size() - 1) {
				int j = i + 1;
				ti = triples.get(i);
				while(j < triples.size()) {
					tj = triples.get(j);
					if(ti.endLine + 1 == tj.startLine || ti.endLine >=tj.startLine && ti.startLine <=tj.startLine) {
						triples.remove(j);
						triples.remove(i);
						ti = new CodeBlockPos(ti.file, ti.startLine, tj.endLine);
						if(triples.isEmpty()) triples.add(ti);
						else triples.add(i, ti);
						j = i + 1;
					}
					else j++;
				}
				i++;
			}

			return triples.stream().sorted((o1, o2) -> o1.startLine < o2.startLine ? -1 : o1.startLine == o2.startLine ? 0 : 1);
		}).flatMap(s -> s).collect(Collectors.toList());
	}

	public int getNbLines() {
		return Stream.concat(statements.stream().map(stat -> stat.getStatmts().get(0).getPosition()),
				conditions.stream().map(stat -> stat.realStatmt.getPosition())).mapToInt(pos-> pos.getEndLine()-pos.getLine()+1).sum();
	}

	@Override
	public String toString() {
		return executable.getSignature()+";"+getNbLines()+";"+getOptimalCodeBlocks().stream().map(b -> b.toString()).collect(Collectors.joining(";"));
	}
}
