package lab.data;

import org.xillium.data.*;
import org.xillium.data.validation.*;
import java.sql.Date;

public class OnymousRequest implements DataObject {
    @required @subtype("EmailAddress")
    public String email;
}
