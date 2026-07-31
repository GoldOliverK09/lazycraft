Full tree visualizer

Several prebuilt scoring algorithms

Item blacklist

Need to access inventory to simulate crafting

Can't use StackedItemContents since it is query only

Need to hook mixin before inventory is packed and in return of canCraft to override craft-ability

Should move out of UI for now and just work on the actual recursive crafter