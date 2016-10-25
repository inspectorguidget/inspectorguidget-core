package fr.inria.diverse.torgen.inspectorguidget.refactoring;

import fr.inria.diverse.torgen.inspectorguidget.analyser.Command;
import fr.inria.diverse.torgen.inspectorguidget.analyser.CommandConditionEntry;
import fr.inria.diverse.torgen.inspectorguidget.analyser.CommandWidgetFinder;
import fr.inria.diverse.torgen.inspectorguidget.filter.BasicFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.MyVariableAccessFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.VariableAccessFilter;
import fr.inria.diverse.torgen.inspectorguidget.helper.SpoonHelper;
import fr.inria.diverse.torgen.inspectorguidget.helper.WidgetHelper;
import org.jetbrains.annotations.NotNull;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.Filter;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Refactors GUI listener that contain multiple commands to extract these last in
 * dedicated listeners.
 */
public class ListenerCommandRefactor {
	private static final Logger LOG = Logger.getLogger("ListenerCommandRefactor");

	private final boolean asLambda;
	private final Command cmd;
	private @NotNull CommandWidgetFinder.WidgetFinderEntry widgets;

	public ListenerCommandRefactor(final @NotNull Command command, final @NotNull CommandWidgetFinder.WidgetFinderEntry entry,
								   final boolean refactAsLambda) {
		asLambda = refactAsLambda;
		widgets = entry;
		cmd = command;
	}

	public void execute() {
		// Removing the possible return located at the end of the listener.
		if(!cmd.getExecutable().getBody().getStatements().isEmpty() &&
			SpoonHelper.INSTANCE.isReturnBreakStatement(cmd.getExecutable().getBody().getLastStatement())) {
			cmd.getExecutable().getBody().getLastStatement().delete();
		}

		widgets.getWidgetUsages().forEach(usage -> {
			// Getting the accesses of the widgets
			List<CtInvocation<?>> invok = usage.accesses.stream().
				// gathering their parent statement.
					map(acc -> acc.getParent(CtStatement.class)).filter(stat -> stat != null).
				// Gathering the method call that matches listener registration: single parameter that is a listener type.
					map(stat -> stat.getElements((CtInvocation<?> exec) -> exec.getExecutable().getParameters().size() == 1 &&
					WidgetHelper.INSTANCE.isListenerClass(exec.getExecutable().getParameters().get(0), exec.getFactory()))).
					flatMap(s -> s.stream()).collect(Collectors.toList());

			if(invok.size()==1) {
				final CtExpression<?> oldParam = invok.get(0).getArguments().get(0);

				if(asLambda) {
					refactorRegistrationAsLambda(invok.get(0));
				}else {
					refactorRegistrationAsAnonClass(invok.get(0));
				}
				removeOldCommand(invok.get(0), oldParam);
			} else {
				LOG.log(Level.SEVERE, "Cannot find a unique widget registration: " + cmd + " " + invok);
			}
		});
	}


	private void removeOldCommand(final @NotNull CtInvocation<?> invok, final @NotNull CtExpression<?> oldParam) {
		cmd.getAllLocalStatmtsOrdered().forEach(elt -> elt.delete());

		final List<CommandConditionEntry> conds = cmd.getConditions();

		if(!conds.isEmpty()) {
			conds.get(0).realStatmt.getParent(CtStatement.class).delete();

			IntStream.range(1, conds.size()).forEach(i -> {
				CtStatement parent = conds.get(i).realStatmt.getParent(CtStatement.class);

				if(parent instanceof CtIf && SpoonHelper.INSTANCE.isEmptyIfStatement((CtIf)parent) ||
					parent instanceof CtSwitch<?> && SpoonHelper.INSTANCE.isEmptySwitch((CtSwitch<?>)parent)) {
					parent.delete();
				}
			});
		}

		if(cmd.getExecutable().getBody().getStatements().isEmpty()) {
			cmd.getExecutable().delete();
			final CtTypeReference<?> typeRef = invok.getExecutable().getParameters().get(0).getTypeDeclaration().getReference();
			cmd.getExecutable().getParent(CtType.class).getSuperInterfaces().remove(typeRef);
		}

		if(oldParam instanceof CtVariableRead) {
			final CtVariableReference<?> var = ((CtVariableRead<?>) oldParam).getVariable();

			if(var instanceof CtLocalVariableReference) {
				final CtLocalVariable<?> varDecl = ((CtLocalVariableReference<?>) var).getDeclaration();

				List<CtVariableAccess<?>> elements = var.getParent(CtBlock.class).getElements(new MyVariableAccessFilter(varDecl));

				if(elements.isEmpty()) {
					varDecl.delete();
				}
			}
		}
	}


	private void removeLastBreakReturn(final @NotNull List<CtElement> stats) {
		if(!stats.isEmpty() && SpoonHelper.INSTANCE.isReturnBreakStatement(stats.get(stats.size()-1))) {
			stats.remove(stats.size()-1);
		}
	}

	private void removeActionCommandStatements() {
		widgets.getWidgetUsages().forEach(usage -> {
			Filter<CtInvocation<?>> filter = new BasicFilter<CtInvocation<?>>() {
				@Override
				public boolean matches(final CtInvocation<?> element) {
					return WidgetHelper.INSTANCE.ACTION_CMD_METHOD_NAMES.stream().
						filter(elt -> element.getExecutable().getSimpleName().equals(elt)).findAny().isPresent();
				}
			};

			// Getting all the set action command and co statements.
			List<CtStatement> actionCmds = usage.accesses.stream().map(access -> access.getParent(CtStatement.class)).
				filter(stat -> stat != null && !stat.getElements(filter).isEmpty()).collect(Collectors.toList());

			// Deleting each set action command and co statement.
			actionCmds.forEach(stat -> stat.delete());

			// Deleting the unused private/protected/package action command names defined as constants or variables (analysing the
			// usage of public variables is time-consuming).
			actionCmds.stream().map(stat -> stat.getElements(new VariableAccessFilter())).flatMap(s -> s.stream()).
				map(access -> access.getVariable().getDeclaration()).distinct().
				filter(var -> var!=null && (var.getVisibility()==ModifierKind.PRIVATE || var.getVisibility()==ModifierKind.PROTECTED ||
					var.getVisibility()==null) && SpoonHelper.INSTANCE.extractUsagesOfVar(var).size()<2).
				forEach(var -> var.delete());
		});
	}

	private void refactorRegistrationAsLambda(final @NotNull CtInvocation<?> invok) {
		final Factory fac = invok.getFactory();
		final CtTypeReference typeRef = invok.getExecutable().getParameters().get(0).getTypeDeclaration().getReference();
		final CtLambda<?> lambda = fac.Core().createLambda();
		final List<CtElement> stats = cmd.getAllLocalStatmtsOrdered().stream().map(stat -> stat.clone()).collect(Collectors.toList());

		removeLastBreakReturn(stats);
		removeActionCommandStatements();

		// Removing the unused local variables of the command.
		SpoonHelper.INSTANCE.removeUnusedLocalVariables(stats);

		if(stats.size()==1 && stats.get(0) instanceof CtExpression<?>) {
			lambda.setExpression((CtExpression)stats.get(0));
		} else {
			final CtBlock block = fac.Core().createBlock();
			stats.stream().filter(stat -> stat instanceof CtStatement).forEach(stat -> block.insertEnd((CtStatement)stat));
			lambda.setBody(block);
		}

		CtParameter<?> oldParam = cmd.getExecutable().getParameters().get(0);
		CtParameter<?> param = fac.Executable().createParameter(lambda, oldParam.getType(), oldParam.getSimpleName());
		lambda.setParameters(Collections.singletonList(param));
		lambda.setType(typeRef);
		invok.setArguments(Collections.singletonList(lambda));
	}


	private void refactorRegistrationAsAnonClass(final @NotNull CtInvocation<?> invok) {
		final Factory fac = invok.getFactory();
		final CtTypeReference typeRef = invok.getExecutable().getParameters().get(0).getTypeDeclaration().getReference();
		final CtClass<?> anonCl = fac.Core().createClass();
		final CtNewClass<?> newCl = fac.Core().createNewClass();
		final List<CtElement> stats = cmd.getAllStatmts().stream().map(stat -> stat.clone()).collect(Collectors.toList());

		removeLastBreakReturn(stats);
		removeActionCommandStatements();

		Optional<CtMethod<?>> m1 = invok.getExecutable().getParameters().get(0).getTypeDeclaration().getMethods().stream().
									filter(meth -> meth.getBody() == null).findFirst();

		if(!m1.isPresent()) {
			LOG.log(Level.SEVERE, "Cannot find an abstract method in the listener interface: " + cmd + " " + invok.getExecutable());
			return;
		}

		final CtMethod<?> meth = m1.get().clone();
		final CtBlock block = fac.Core().createBlock();
		final CtConstructor cons = fac.Core().createConstructor();
		cons.setBody(fac.Core().createBlock());
		cons.setImplicit(true);
		meth.setBody(block);
		meth.getParameters().get(0).setSimpleName(cmd.getExecutable().getParameters().get(0).getSimpleName());
		meth.setModifiers(Collections.singleton(ModifierKind.PUBLIC));
		stats.stream().filter(stat -> stat instanceof CtStatement).forEach(stat -> block.insertEnd((CtStatement)stat));

		anonCl.setConstructors(Collections.singleton(cons));
		anonCl.setMethods(Collections.singleton(meth));
		anonCl.setSuperInterfaces(Collections.singleton(typeRef));
		anonCl.setSimpleName("1");
		newCl.setAnonymousClass(anonCl);

		CtExecutableReference ref = cons.getReference();
		ref.setType(typeRef);
		newCl.setExecutable(ref);

		invok.setArguments(Collections.singletonList(newCl));
	}
}