package fr.inria.diverse.torgen.inspectorguidget.analyser;

import fr.inria.diverse.torgen.inspectorguidget.filter.BasicFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.FindElementFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.MyVariableAccessFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.ReturnFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.SpecificStringLiteralFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.StringLiteralFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.ThisAccessFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.TypeRefFilter;
import fr.inria.diverse.torgen.inspectorguidget.filter.VariableAccessFilter;
import fr.inria.diverse.torgen.inspectorguidget.helper.LoggingHelper;
import fr.inria.diverse.torgen.inspectorguidget.helper.SpoonHelper;
import fr.inria.diverse.torgen.inspectorguidget.helper.WidgetHelper;
import fr.inria.diverse.torgen.inspectorguidget.processor.WidgetProcessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtTypedElement;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.declaration.ParentNotInitializedException;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.Filter;

/**
 * An analyser to find the widget(s) that produce(s) a given command.
 */
public class CommandWidgetFinder {
	public static final @NotNull Logger LOG = Logger.getLogger("CommandWidgetFinder");

	static {
		LOG.setLevel(LoggingHelper.INSTANCE.loggingLevel);
	}

	private final @NotNull List<Command> cmds;
	private final @NotNull Map<Command, WidgetFinderEntry> results;
	private final @NotNull List<WidgetProcessor.WidgetUsage> widgetUsages;

	/**
	 * Creates the analyser.
	 * @param commands the set of commands to analyse.
	 */
	public CommandWidgetFinder(final @NotNull List<Command> commands, final @NotNull List<WidgetProcessor.WidgetUsage> usages) {
		super();
		cmds = commands;
		results = new IdentityHashMap<>();
		widgetUsages = usages;
	}

	/**
	 * Executes the analysis.
	 */
	public void process() {
		cmds.parallelStream().forEach(cmd -> process(cmd));
	}

	private void process(final @NotNull Command cmd) {
		final WidgetFinderEntry entry = new WidgetFinderEntry();

		synchronized(results) {
			results.put(cmd, entry);
		}

		CtClass<?> listenerClass = null;
		try {
			CtElement cmdParent = cmd.getExecutable().getParent();

			if(cmdParent instanceof CtClass<?>) {
				listenerClass = (CtClass<?>) cmdParent;
			}
		}catch(ParentNotInitializedException ex) {
			LOG.log(Level.INFO, "ParentNotInitializedException in process", ex);
		}

//		long time = System.currentTimeMillis();
		entry.setRegisteredWidgets(getAssociatedListenerVariable(cmd));
//		System.out.println("ANALYSIS #1 in: " + (System.currentTimeMillis()-time));
//		time = System.currentTimeMillis();
		entry.setWidgetsUsedInConditions(getVarWidgetUsedInCmdConditions(cmd));
//		System.out.println("ANALYSIS #2 in: " + (System.currentTimeMillis()-time));
//		time = System.currentTimeMillis();
		entry.setWidgetClasses(getWidgetClass(cmd));
//		System.out.println("ANALYSIS #3 in: " + (System.currentTimeMillis()-time));
//		time = System.currentTimeMillis();
		entry.setWidgetsFromSharedVars(checkListenerMatching(listenerClass, matchWidgetsUsagesWithCmdConditions(cmd)));
//		System.out.println("ANALYSIS #4 in: " + (System.currentTimeMillis()-time));
//		time = System.currentTimeMillis();
		entry.setWidgetsFromStringLiterals(checkListenerMatching(listenerClass, matchWidgetsUsagesWithStringsInCmdConditions(cmd)));
//		System.out.println("ANALYSIS #5 in: " + (System.currentTimeMillis()-time));

		entry.preciseWidgets(cmd);
	}


	/**
	 * Checks that the matchings correspond to the listener that contains the command:
	 * a reference of the listener is searched in the usages of the identified widgets and is compared
	 * to the listener of the command to determine whether this widget really matches the command.
	 * This analysis does not work with toolkit that does not require listener registration such as
	 * with JavaFX where a listener method can be associated with the widget directly in the FXML.
	 * @param listenerClass The listener class of the command.
	 * @param cmdWidgetMatches The list of the widgets that may correspond to the command.
	 * @param <T> The type of the matching.
	 * @return The filtered list of widgets.
	 */
	private @NotNull <T extends CmdWidgetMatch> List<T> checkListenerMatching(final @Nullable CtClass<?> listenerClass,
																			 final @NotNull List<T> cmdWidgetMatches) {
		if(listenerClass==null) return cmdWidgetMatches;

		final CtTypeReference<?> listRef = listenerClass.getReference();
		final Filter<CtTypedElement<?>> filt = new BasicFilter<>(CtTypedElement.class);

		cmdWidgetMatches.removeIf(m ->
			// Removing if in the statement of the acess there is a reference to the current listener class.
			!m.usage.accesses.stream().filter(a -> a.getParent(CtStatement.class).getElements(filt).stream().
			map(var -> var.getType()).filter(ty -> ty.equals(listRef)).findFirst().isPresent()).findFirst().isPresent());
		return cmdWidgetMatches;
	}


	/**
	 * Example:
	 * if(e.getActionCommand().equals("FOO")){...}
	 * ...
	 * button.setActionCommand("FOO");
	 * @param cmd The command to analyse.
	 */
	private List<StringLitMatch> matchWidgetsUsagesWithStringsInCmdConditions(final @NotNull Command cmd) {
		final StringLiteralFilter stringLiteralFilter = new StringLiteralFilter();

		final Set<CtLiteral<?>> stringliterals = cmd.getConditions().parallelStream().
			// Must ignore the conditions of if statements when in an else block (in this case the effective if statement is a negation of the
			// real conditions, so they are different)
				filter(cond -> cond.realStatmt==cond.effectiveStatmt || cond.realStatmt.isParentInitialized() && !(cond.realStatmt.getParent() instanceof CtIf)).
			// Getting the variables used in the conditions
				map(cond -> cond.effectiveStatmt.getElements(stringLiteralFilter)).flatMap(s -> s.stream()).
			// Keeping those that declaration are not null
			// Collecting them
				distinct().collect(Collectors.toCollection(HashSet::new));

		return widgetUsages.stream().map(usage -> {
			// Getting the code statement that uses the variable
			 return usage.accesses.stream().map(acc -> acc.getParent(CtStatement.class)).filter(stat -> stat != null).
				// Looking for the variables used in the conditions in the code statement
				map(stat -> stringliterals.stream().filter(varr -> !stat.getElements(new FindElementFilter(varr, false)).isEmpty()).
					collect(Collectors.toList())).
					filter(list -> !list.isEmpty()).
					map(var -> new StringLitMatch(usage, var));
			}).flatMap(s -> s).collect(Collectors.toList());
	}


	/**
	 * Example:
	 * if(e.getActionCommand().equals(FOO)){...}
	 * ...
	 * button.setActionCommand(FOO);
	 * @param cmd The command to analyse.
	 */
	private List<VarMatch> matchWidgetsUsagesWithCmdConditions(final @NotNull Command cmd) {
		final VariableAccessFilter filter = new VariableAccessFilter();

		final Set<CtVariable<?>> vars = cmd.getConditions().parallelStream().
			// Must ignore the conditions of if statements when in an else block (in this case the effective if statement is a negation of the
			// real conditions, so they are different)
			filter(cond -> cond.realStatmt==cond.effectiveStatmt || cond.realStatmt.isParentInitialized() && !(cond.realStatmt.getParent() instanceof CtIf)).
			// Getting the variables used in the conditions
			map(cond -> cond.effectiveStatmt.getElements(filter)).flatMap(s -> s.stream()).
			// Keeping those that declaration are not null
			map(acc -> acc.getVariable().getDeclaration()).filter(var -> var!=null).
			// Collecting them
			distinct().collect(Collectors.toCollection(HashSet::new));

		return widgetUsages.parallelStream().
			map(usage -> usage.accesses.parallelStream().filter(m -> {
				// Ignoring the statements that are parts of a listener method. The statements that must be analysed
				// or those that configure the widgetUsages.
				try {
					CtExecutable<?> ex = m.getParent(CtExecutable.class);
					return ex == null || !WidgetHelper.INSTANCE.isListenerClassMethod(ex);
				}catch(ParentNotInitializedException ex) {
					return true;
				}
			}).
			// Getting the code statement that uses the variable
			map(varac -> varac.getParent(CtStatement.class)).
			filter(stat -> stat != null).
			// Looking for the variables used in the conditions in the code statement
			map(stat -> vars.stream().filter(varr -> !stat.getElements(new MyVariableAccessFilter(varr)).isEmpty()).
			collect(Collectors.toList())).
			filter(list -> !list.isEmpty()).
			map(var -> new VarMatch(usage, var))).
			// Collecting all the variables used in both the command's conditions and the code statements that configure widgetUsages
			flatMap(s -> s).collect(Collectors.toList());

//		if(widget.size()>1) {
//			System.err.println("MORE THAN ONE WIDGET FOUND USING VARIABLES: " + widgetUsages + " " + cmd);
//		}
	}


	@SuppressWarnings("rawtypes")
	private @NotNull Optional<CtClass<?>> getWidgetClass(final @NotNull Command cmd) {
		final CtExecutable<?> listenerMethod = cmd.getExecutable();
		final CtInvocation<?> inv = listenerMethod.getParent(CtInvocation.class);

		if(inv!=null || !listenerMethod.isParentInitialized() || !(listenerMethod.getParent() instanceof CtClass<?>))
			return Optional.empty();

		Optional<CtClass> ctClass = listenerMethod.getParent().getElements(new ThisAccessFilter(false)).stream().
			filter(thisacc -> thisacc.isParentInitialized() && thisacc.getParent() instanceof CtInvocation<?>).
			map(thisacc -> {
				final CtInvocation<?> invok = (CtInvocation<?>) thisacc.getParent();
				final CtExpression<?> target = invok.getTarget();
				CtClass clazz;

				if(target instanceof CtThisAccess<?> && WidgetHelper.INSTANCE.isTypeRefAToolkitWidget(invok.getExecutable().getDeclaringType()))
					clazz = (CtClass) ((CtThisAccess<?>) target).getType().getDeclaration();
				else clazz = null;

				return Optional.ofNullable(clazz);
			}).filter(clazz -> clazz.isPresent()).findFirst().orElseGet(() -> Optional.empty());

		return Optional.ofNullable(ctClass.orElseGet(() -> null));
	}


	/**
	 * Identifies the widgetUsages used the conditions of the given command.
	 * @param cmd The comand to analyse
	 * @return The list of the references to the widgetUsages used in the conditions.
	 */
	private @NotNull Set<WidgetProcessor.WidgetUsage> getVarWidgetUsedInCmdConditions(final @NotNull Command cmd) {
		final TypeRefFilter filter = new TypeRefFilter(WidgetHelper.INSTANCE.getWidgetTypes(cmd.getExecutable().getFactory()));
		// Getting the widget types used in the conditions.
		final List<CtTypeReference<?>> types = cmd.getConditions().stream().map(cond -> cond.realStatmt.getElements(filter)).
											flatMap(s -> s.stream()).distinct().collect(Collectors.toList());

		// Getting the widget usages which variable is used in the conditions.
		return widgetUsages.parallelStream().filter(u ->
			types.stream().filter(w -> {
				try{
					final CtVariableReference<?> parent = w.getParent(CtVariableReference.class);
					return parent != null && u.widgetVar == parent.getDeclaration();
				}catch(ParentNotInitializedException ex) {
					return false;
				}
			}).findFirst().isPresent()).collect(Collectors.toSet());
	}


//	private Optional<CtClass<?>> getWidgetClass(final @NotNull CtThisAccess<?> thisaccess) {
//		if(WidgetHelper.INSTANCE.isTypeRefAWidget(thisaccess.getType())) {
//			return Optional.ofNullable((CtClass<?>)thisaccess.getType().getDeclaration());
//		}
//		return Optional.empty();
//	}


	/**
	 * Identifies the widget on which the listener is added.
	 * @param cmd The command to analyse.
	 * @return The reference to the widget or nothing.
	 */
	private @NotNull Set<WidgetProcessor.WidgetUsage> getAssociatedListenerVariable(final @NotNull Command cmd) {
		final CtExecutable<?> listenerMethod = cmd.getExecutable();
		final CtInvocation<?> invok = listenerMethod.getParent(CtInvocation.class);

		if(invok==null) {
			if(listenerMethod.isParentInitialized() && listenerMethod.getParent() instanceof CtClass<?>)
				return getAssociatedListenerVariableThroughClass((CtClass<?>)listenerMethod.getParent(), cmd);
			return Collections.emptySet();
		}

		Optional<WidgetProcessor.WidgetUsage> lisVar = getAssociatedListenerVariableThroughInvocation(invok);
		return lisVar.isPresent() ? Collections.singleton(lisVar.get()) : Collections.emptySet();
	}


	/**
	 * Example: myWidget.addActionListener(this)
	 * @param clazz The class to analyse.
	 * @return The possible widget.
	 */
	private Set<WidgetProcessor.WidgetUsage> getAssociatedListenerVariableThroughClass(final @NotNull CtClass<?> clazz, final @NotNull Command cmd) {
		// Looking for 'this' usages
		final CtType<?> interf = WidgetHelper.INSTANCE.getListenerInterface(cmd.getExecutable()).orElse(null);

		Set<WidgetProcessor.WidgetUsage> ref = clazz.getElements(new ThisAccessFilter(false)).stream().
			// Keeping the 'this' usages that are parameters of a method call
				filter(thisacc -> thisacc.isParentInitialized() && thisacc.getParent() instanceof CtInvocation<?> &&
					// Checking that the type of the listener widget matches the listener method of the command
					((CtInvocation<?>)thisacc.getParent()).getExecutable().getParameters().size()==1 &&
					((CtInvocation<?>)thisacc.getParent()).getExecutable().getParameters().get(0).getTypeDeclaration().equals(interf)).
				map(thisacc -> getAssociatedListenerVariableThroughInvocation((CtInvocation<?>) thisacc.getParent())).
				filter(usage -> usage.isPresent()).map(usage -> usage.get()).collect(Collectors.toSet());

		// Looking for associations in super classes.
		final CtType<?> superclass = clazz.getSuperclass()==null ? null : clazz.getSuperclass().getDeclaration();
		if(superclass instanceof CtClass<?>) {
			ref.addAll(getAssociatedListenerVariableThroughClass((CtClass<?>) superclass, cmd));
		}

		return ref;
	}


	private Optional<WidgetProcessor.WidgetUsage> getMatchingWidgetUsage(final CtVariable<?> var) {
		return widgetUsages.parallelStream().filter(u -> u.widgetVar == var).findFirst();
	}


	/**
	 * Example: myWidget.addActionListener(() ->...);
	 * @param invok The invocation from which the widget will be retieved.
	 * @return The possible widget.
	 */
	private Optional<WidgetProcessor.WidgetUsage> getAssociatedListenerVariableThroughInvocation(final @NotNull CtInvocation<?> invok) {
		if(!WidgetHelper.INSTANCE.isTypeRefAToolkitWidget(invok.getExecutable().getDeclaringType()))
			return Optional.empty();

		final CtExpression<?> target = invok.getTarget();

		if(target instanceof CtVariableAccess<?>) {
			// Looking in the widget usages which widget matches this variable.
			return getMatchingWidgetUsage(((CtVariableAccess<?>)target).getVariable().getDeclaration());
		}else if(target instanceof CtThisAccess<?> || target instanceof CtTypeAccess<?>) {
			// First instanceof: 'This' accesses are supported in getWidgetClass.
			// Second instanceof: For example: JOptionPane.showConfirmDialog(...), so not related to a variable.
		} else if(target instanceof CtInvocation<?>) {
			final CtClass<Object> clazz = target.getFactory().Class().get(((CtInvocation<?>) target).getExecutable().getDeclaringType().getQualifiedName());

			if(clazz==null) {
				LOG.log(Level.SEVERE, () -> "Cannot find the class " + ((CtInvocation<?>) target).getExecutable().getDeclaringType().getQualifiedName());
				return Optional.empty();
			}

			List<CtMethod<?>> methods = clazz.getMethodsByName(((CtInvocation<?>) target).getExecutable().getSimpleName());

			if(methods.size()==1) {
				List<CtReturn<?>> returns = methods.get(0).getBody().getElements(new ReturnFilter());

				if(returns.size()==1 && returns.get(0).getReturnedExpression() instanceof CtVariableAccess<?>) {
					return getMatchingWidgetUsage(((CtVariableAccess<?>)returns.get(0).getReturnedExpression()).getVariable().getDeclaration());
				}
				LOG.log(Level.SEVERE, () -> "Unsupported return statement(s): " + returns + " in " + methods.get(0) +
					(returns.get(0).getReturnedExpression()==null ? "" : ", " + returns.get(0).getReturnedExpression().getClass()));
			}else {
				LOG.log(Level.SEVERE, () -> "Incorrect number of methods found for the invocation: " + target + ", methods: " + methods);
			}
		}
		else {
			LOG.log(Level.SEVERE, () -> "INVOCATION TARGET TYPE NOT SUPPORTED: " + target.getClass() + " : " + invok + " " +
								SpoonHelper.INSTANCE.formatPosition(invok.getPosition()));
		}

		return Optional.empty();
	}


	/**
	 * @return A unmodifiable map of the results of the process.
	 */
	public @NotNull Map<Command, WidgetFinderEntry> getResults() {
		synchronized(results) { return Collections.unmodifiableMap(results); }
	}


	public static final class WidgetFinderEntry {
		private @NotNull Set<WidgetProcessor.WidgetUsage> registeredWidgets;
		private @NotNull Set<WidgetProcessor.WidgetUsage> widgetsUsedInConditions;
		private @NotNull Optional<CtClass<?>> widgetClasses;
		private @NotNull List<VarMatch> widgetsFromSharedVars;
		private @NotNull List<StringLitMatch> widgetsFromStringLiterals;

		private WidgetFinderEntry() {
			super();
			registeredWidgets = Collections.emptySet();
			widgetsUsedInConditions = Collections.emptySet();
			widgetClasses = Optional.empty();
			widgetsFromSharedVars = Collections.emptyList();
			widgetsFromStringLiterals = Collections.emptyList();
		}

		/**
		 * @return All the usages found. Cannot be null.
		 */
		public Set<WidgetProcessor.WidgetUsage> getWidgetUsages() {
			final Set<WidgetProcessor.WidgetUsage> usages = new HashSet<>();

			if(!widgetsFromStringLiterals.isEmpty()) {
				usages.addAll(widgetsFromStringLiterals.stream().map(lit -> lit.usage).collect(Collectors.toSet()));
			}

			if(!widgetsFromSharedVars.isEmpty()) {
				usages.addAll(widgetsFromSharedVars.stream().map(lit -> lit.usage).collect(Collectors.toSet()));
			}

			if(!widgetsUsedInConditions.isEmpty()) {
				usages.addAll(widgetsUsedInConditions);
			}

			// Getting the widget registration only if no widget has already been identified.
			if(usages.isEmpty() && !registeredWidgets.isEmpty()) {
				usages.addAll(registeredWidgets);
			}

			return usages;
		}

		public @NotNull List<StringLitMatch> getWidgetsFromStringLiterals() {
			return Collections.unmodifiableList(widgetsFromStringLiterals);
		}

		public @NotNull List<VarMatch> getWidgetsFromSharedVars() {
			return Collections.unmodifiableList(widgetsFromSharedVars);
		}

		public @NotNull Set<WidgetProcessor.WidgetUsage> getRegisteredWidgets() {
			return Collections.unmodifiableSet(registeredWidgets);
		}

		public @NotNull Set<WidgetProcessor.WidgetUsage> getWidgetsUsedInConditions() {
			return Collections.unmodifiableSet(widgetsUsedInConditions);
		}

		public @NotNull Optional<CtClass<?>> getWidgetClasses() {
			return widgetClasses;
		}

		public void setWidgetsFromStringLiterals(final @NotNull List<StringLitMatch> widgetsFromStringLiterals) {
			this.widgetsFromStringLiterals = widgetsFromStringLiterals;
		}

		public void setWidgetsFromSharedVars(final @NotNull List<VarMatch> widgetsFromSharedVars) {
			this.widgetsFromSharedVars = widgetsFromSharedVars;
		}

		private void setRegisteredWidgets(final @NotNull Set<WidgetProcessor.WidgetUsage> registeredWidgets) {
			this.registeredWidgets = registeredWidgets;
		}

		private void setWidgetsUsedInConditions(final @NotNull Set<WidgetProcessor.WidgetUsage> widgetsUsedInConditions) {
			this.widgetsUsedInConditions = widgetsUsedInConditions;
		}

		public void setWidgetClasses(final @NotNull Optional<CtClass<?>> widgetClasses) {
			this.widgetClasses = widgetClasses;
		}

		public long getNbDistinctWidgets() {
			return Stream.concat(Stream.concat(Stream.concat(registeredWidgets.stream(), widgetsUsedInConditions.stream()),
								widgetsFromSharedVars.stream().map(v -> v.usage)), widgetsFromStringLiterals.stream().map(v -> v.usage)).distinct().count()
					+(widgetClasses.isPresent()?1:0);
		}

		public List<CtVariable<?>> getDistinctUsedWidgets() {
			return Stream.concat(Stream.concat(new ArrayList<>(getWidgetsFromSharedVars()).stream().map(u -> u.usage.widgetVar),
				new ArrayList<>(getWidgetsUsedInConditions()).stream().map(u -> u.widgetVar)),
				new ArrayList<>(getWidgetsFromStringLiterals()).stream().map(u -> u.usage.widgetVar)).
				distinct().collect(Collectors.toList());
		}


		public List<CtVariable<?>> getSuppostedAssociatedWidget() {
			final List<CtVariable<?>> widgets = getDistinctUsedWidgets();

			switch(widgets.size()) {
				case 0:
					return registeredWidgets.stream().map(w -> w.widgetVar).collect(Collectors.toList());
				case 1:
					return widgets;
				default:
					List<CtVariable<?>> reg = registeredWidgets.stream().map(w -> w.widgetVar).filter(w -> widgets.contains(w)).collect(Collectors.toList());
					if(reg.isEmpty())
						return widgets;
					return reg;
			}
		}


		/**
		 *
		 */
		private void preciseWidgets(final @NotNull Command cmd) {
			// This optimisation is performed only when several widgets are registered to the listener:
			// this method will try to find out which widgets really concern the command.
			if(registeredWidgets.size()<2) return;

			registeredWidgets.removeIf(w -> {
				if(widgetsFromSharedVars.isEmpty() && widgetsFromStringLiterals.isEmpty()) return false;

				boolean ok = widgetsFromSharedVars.stream().map(u -> u.vars).flatMap(s -> s.stream()).filter(var -> {
					final MyVariableAccessFilter filter = new MyVariableAccessFilter(var);
					// Looking the usages a variable access that corresponds to the variable used to register the widget to the listener.
					return w.accesses.stream().map(a -> a.getParent(CtStatement.class)).
						filter(a -> !a.getElements(filter).isEmpty()).findFirst().isPresent();
				}).findFirst().isPresent();

				// If no usage found
				if(!ok) // Try with the string literals.
					ok = widgetsFromStringLiterals.stream().map(u -> u.stringlit).flatMap(s -> s.stream()).filter(var -> {
						final SpecificStringLiteralFilter filter = new SpecificStringLiteralFilter(var);
						return w.accesses.stream().map(a -> a.getParent(CtStatement.class)).
							filter(a -> !a.getElements(filter).isEmpty()).findFirst().isPresent();
					}).findFirst().isPresent();

				// If no usage found
//				if(!ok) // Try with the widgets used in the conditions (this optimisation seems to be already done.
//					ok = widgetsUsedInConditions.stream().filter(var -> var.widgetVar == w.widgetVar).findFirst().isPresent();

				return !ok;
			});
		}
	}



	public abstract static class CmdWidgetMatch {
		public final WidgetProcessor.WidgetUsage usage;

		public CmdWidgetMatch(final @NotNull WidgetProcessor.WidgetUsage u) {
			super();
			usage = u;
		}
	}

	public static class StringLitMatch extends CmdWidgetMatch {
		public final List<CtLiteral<?>> stringlit;

		public StringLitMatch(final @NotNull WidgetProcessor.WidgetUsage u, final @NotNull List<CtLiteral<?>> lit) {
			super(u);
			stringlit = lit;
		}

		@Override
		public String toString() {
			return "StringLitMatch{" + "string lit=" + stringlit + ", usage: " + usage +'}';
		}
	}

	public static class VarMatch extends CmdWidgetMatch {
		public final List<CtVariable<?>> vars;

		public VarMatch(final @NotNull WidgetProcessor.WidgetUsage u, final @NotNull List<CtVariable<?>> var) {
			super(u);
			vars = var;
		}

		@Override
		public String toString() {
			return "StringLitMatch{" + "vars=" + vars + ", usage: " + usage +'}';
		}
	}
}
