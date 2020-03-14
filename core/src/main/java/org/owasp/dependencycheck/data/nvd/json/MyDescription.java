
package org.owasp.dependencycheck.data.nvd.json;

import java.util.ArrayList;
import java.util.List;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class MyDescription {

    /**
     * 
     * (Required)
     * 
     */
    @SerializedName("description_data")
    @Expose
    private List<LangString> descriptionData = new ArrayList<LangString>();

    /**
     * 
     * (Required)
     * 
     */
    public List<LangString> getDescriptionData() {
        return descriptionData;
    }

    /**
     * 
     * (Required)
     * 
     */
    public void setDescriptionData(List<LangString> descriptionData) {
        this.descriptionData = descriptionData;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(Description.class.getName()).append('@').append(Integer.toHexString(System.identityHashCode(this))).append('[');
        sb.append("descriptionData");
        sb.append('=');
        sb.append(((this.descriptionData == null)?"<null>":this.descriptionData));
        sb.append(',');
        if (sb.charAt((sb.length()- 1)) == ',') {
            sb.setCharAt((sb.length()- 1), ']');
        } else {
            sb.append(']');
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = ((result* 31)+((this.descriptionData == null)? 0 :this.descriptionData.hashCode()));
        return result;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if ((other instanceof MyDescription) == false) {
            return false;
        }
        MyDescription rhs = ((MyDescription) other);
        return ((this.descriptionData == rhs.descriptionData)||((this.descriptionData!= null)&&this.descriptionData.equals(rhs.descriptionData)));
    }

}
