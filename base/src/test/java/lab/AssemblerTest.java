package lab;

import java.awt.*;
import java.util.logging.Level;
import javax.swing.*;
import org.xillium.base.beans.*;
import org.testng.annotations.*;


/**
 * The default implementation of an object factory that creates objects from class names and arguments.
 */
public class AssemblerTest {

    @Test(groups={"functional"})
    public void testAssember() throws Exception {
        Object bean = new XMLBeanAssembler(new DefaultObjectFactory()).build("src/test/java/lab/swing.xml");
        if (bean.getClass() == JFrame.class) {
            JFrame frame = (JFrame)bean;
            frame.pack();
            frame.setVisible(true);
        } else {
            System.out.println(Beans.toString(bean));

            JFrame frame = new JFrame("Object Inspector");
            JTree tree = new JTree(new BeanNode(bean));
            frame.getContentPane().add(new JScrollPane(tree), BorderLayout.CENTER);
            frame.pack();
            frame.setVisible(true);
        }
    }
}
