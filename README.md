# InspectorGuidget
A tool to find blob listeners in Java code

# Examples of Blob Listener in other GUI toolkits

## SWT

```
public class SelectionEventListener extends AbstractListener implements Listener {
	private int selectMethod;
	
	@Override public void handleEvent(Event event) {
		if (event.type == SWT.MouseDown){
			handleMouse(event);
			return;
		}
		if (!checkCreate(event)) return;
		AbstractAction action = null;

		if (event.widget instanceof MenuItem){
			action = new SimpleSelectionAction(event.type);
			
		} else if (event.widget instanceof ToolItem){
			System.out.println("tool");
			
		} else if (event.widget instanceof Text){
			System.out.println("text");
			
		} else if (event.widget instanceof Button){
			action = new SimpleSelectionAction(event.type);
			action.setShell(((Button) event.widget).getShell());

		} else if (event.widget instanceof Tree){
			StringBuilder sb = new StringBuilder();
			Tree tr = (Tree) event.widget;
			System.out.println("tree");
			//do not handle events of workbench window 
			
			if ((!(tr.getShell() == PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell())) ||
					selectMethod == 3){
				System.out.println("the shell "+tr.getShell());
				TreeItem[] items = ((Tree) event.widget).getSelection();

				for (TreeItem item : items){
					//System.out.println("tree item "+ item.toString());
					sb.append(item.getText());
					TreeItem selected = item;

					while(selected.getParentItem() != null){
						TreeItem parentIt = selected.getParentItem();
						//System.out.println(parentIt);
						sb.insert(0, "/");
						sb.insert(0, parentIt.getText());
						selected = parentIt;
					}
				}
				System.out.println(sb.toString());
				action = new SelectionAction(event.type);
				action.process(event);
				interactionHistory.addInteraction(action);
			}
			return;
		}
		
		else if (event.widget instanceof Table){
			System.out.println("table");
			if (event.detail == SWT.CHECK)
				System.out.println("check");
			String type = event.type == SWT.DefaultSelection ? SelectionAction.DEFAULT_SELECT : SelectionAction.ITEM_SELECT;
			action = new SelectionAction(event.type);
			((SelectionAction) action).addPart(DeliasUtils.getActivePartTitle());
		}
        ...
```
[https://github.com/beccsi/delias/blob/a58df6ef45328d6b1495c424293e776a02e33e48/de.mobis.delias/src/org/teamweaver/delias/commons/SelectionEventListener.java](source)
[examples/delias-a58df6ef45328d6b1495c424293e776a02e33e48.zip](cache)

```
    @Override
    public void handleEvent(Event event) {
        if (!(event.widget instanceof Button)){
            return;
        }
        Button b = (Button) event.widget;
        if (b.getText().equals(addButtonText)) {
            add();
        } else if (b.getText().equalsIgnoreCase(removeButtonText)) {
            remove();
        }
    }
```

[https://github.com/Unidata/awips2/blob/c9f28fd5943170b88cac2e3af3b0234ac444b705/cave/com.raytheon.uf.viz.collaboration.ui/src/com/raytheon/uf/viz/collaboration/ui/login/ServerListListener.java](source)
[examples/awips2-upc_14.4.1.zip](cache)

## GWT

```
	@Override
	public void onClick(ClickEvent event) {
		if (event.getSource() == clearDatastoreButton) {
			clearDatastore();
		} else if (event.getSource() == populateDatastoreButton) {
			populateDatastore();
		} else if (event.getSource() == getPopulateDatastoreCountButton) {
			getPopulateDatastoreCount();
		} else if (event.getSource() == refreshEditModeOn) {
			refreshEditMode(true);
		} else if (event.getSource() == refreshEditModeOff) {
			refreshEditMode(false);
		} else if (event.getSource() == emailOpenedTree) {
			emailOpenedTree();
		} else if (event.getSource() == showOpenedTree) {
			showOpenedTree();
		}
	}
```

[https://github.com/zackriegman/ConceptMapper/blob/12ac53b27950e0b1c12dc803e57c355c675f4905/src/org/argmap/client/ModeAdmin.java](source)
[examples/ConceptMapper-12ac53b27950e0b1c12dc803e57c355c675f4905.zip](cache)

